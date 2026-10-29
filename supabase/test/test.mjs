import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';

const db = new PGlite();
const schema = fs.readFileSync(process.argv[2], 'utf8');

// --- Minimal Supabase stand-ins ---
await db.exec(`
  create role anon nologin;
  create role authenticated nologin;
  grant usage on schema public to anon, authenticated;
  -- mimic Supabase default privileges
  alter default privileges in schema public grant all on tables to anon, authenticated;
  alter default privileges in schema public grant execute on functions to anon, authenticated;
  create schema auth;
  grant usage on schema auth to anon, authenticated;
  create table auth.users (id uuid primary key, email text);
  create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.sub', true), '')::uuid $$;
  grant execute on function auth.uid() to anon, authenticated;
  insert into auth.users values
    ('11111111-1111-1111-1111-111111111111', 'mentor@college.edu'),
    ('22222222-2222-2222-2222-222222222222', 'other@college.edu');
`);

await db.exec(schema);
await db.exec(schema); // idempotent re-run

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('  ok   ', msg); } else { fail++; console.log('  FAIL ', msg); } };
async function expectError(sql, params, code, msg) {
  try { await db.query(sql, params); ok(false, msg + ' (no error)'); }
  catch (e) { ok(String(e.message).includes(code), `${msg} -> ${e.message}`); }
}
const asRole = async (role, sub) => {
  await db.exec(`reset role; select set_config('request.jwt.sub', '${sub ?? ''}', false); set role ${role};`);
};
const asOwner = () => db.exec(`reset role;`);

const H = (c) => c.repeat(64).slice(0, 64);
const DEV_A = H('a'), DEV_B = H('b'), DEV_C = H('c');

console.log('Setup');
const r = await db.query(`select public.setup_group('Block A', 'blocka25', 'MENTOR@college.edu') as m`);
ok(r.rows[0].m.includes('BLOCKA25'), 'setup_group creates group + mentor: ' + r.rows[0].m);
await db.query(`select public.setup_group('Block B', 'BLOCKB25', 'other@college.edu')`);
await expectError(`select public.setup_group('X','X1','nobody@x.com')`, [], 'No login found', 'setup_group rejects unknown mentor');

console.log('Phone (anon) permissions');
await asRole('anon');
await expectError(`select public.setup_group('Y','Y1','mentor@college.edu')`, [], 'permission denied', 'anon cannot run setup_group');
await expectError(`select * from public.participants`, [], 'permission denied', 'anon cannot read participants');
await expectError(`select * from public.daily_usage`, [], 'permission denied', 'anon cannot read usage');
await expectError(`select * from public.device_secrets`, [], 'permission denied', 'anon cannot read tokens');
await expectError(`insert into public.daily_usage(participant_id, day, screen_time_sec) values (gen_random_uuid(), current_date, 5)`, [], 'permission denied', 'anon cannot write tables directly');

console.log('Registration rules');
const reg = (code, name, roll, dev, src = 'drm') =>
  db.query(`select public.register_device($1,$2,$3,$4,$5,'Pixel 7','14','1.0') as r`, [code, name, roll, dev, src]).then(x => x.rows[0].r);
ok((await db.query(`select public.register_device('WRONG','Asha','R1',$1) as r`, [DEV_A])).rows[0].r.error === 'INVALID_CODE', 'wrong join code rejected (returned as error)');
await expectError(`select public.register_device('BLOCKA25','Asha','R1','abc')`, [], 'BAD_DEVICE_ID', 'malformed device id rejected');
await expectError(`select public.register_device('BLOCKA25','A','R1',$1)`, [DEV_A], 'BAD_NAME', 'too-short name rejected');
const a1 = await reg(' blocka25 ', 'Asha Kumari', 'cs101', DEV_A);
ok(a1.token && a1.token.length === 64 && a1.roll_no === 'CS101' && a1.relinked === false, 'student A registers (code case/space-insensitive, roll uppercased)');
await expectError(`select public.register_device('BLOCKA25','Ravi','CS102',$1)`, [DEV_A], 'DEVICE_ALREADY_REGISTERED', 'same phone, different student -> rejected (covers 2nd user profile, same DRM id)');
await expectError(`select public.register_device('BLOCKB25','Asha','CS101',$1)`, [DEV_A], 'DEVICE_ALREADY_REGISTERED', 'same phone, other group -> rejected');
await expectError(`select public.register_device('BLOCKA25','Asha 2nd phone','CS101',$1)`, [DEV_B], 'ROLL_ALREADY_REGISTERED', 'same roll no on a second phone -> rejected');
const a2 = await reg('BLOCKA25', 'whatever', 'CS101', DEV_A);
ok(a2.relinked === true && a2.participant_id === a1.participant_id && a2.token !== a1.token && a2.full_name === 'Asha Kumari', 'reinstall on same phone re-links, rotates token, keeps name');
const b1 = await reg('BLOCKA25', 'Ravi Singh', 'CS102', DEV_B, 'android_id');
ok(b1.relinked === false, 'student B registers on own phone');
const c1 = await reg('BLOCKB25', 'Meera', 'EE201', DEV_C);

console.log('Uploads');
const up = (tok, dev, days) => db.query(`select public.upload_usage($1,$2,$3::jsonb,'1.0') as r`, [tok, dev, JSON.stringify(days)]).then(x => x.rows[0].r);
const today = (await db.query(`select current_date::text d`)).rows[0].d;
const yday = (await db.query(`select (current_date-1)::text d`)).rows[0].d;
const apps = Array.from({ length: 30 }, (_, i) => ({ pkg: 'p' + i, name: 'App ' + i, sec: 100 * (30 - i), opens: i }));
await expectError(`select public.upload_usage($1,$2,'[]'::jsonb)`, [a1.token, DEV_A], 'INVALID_TOKEN', 'old token stops working after re-link');
await expectError(`select public.upload_usage($1,$2,'[]'::jsonb)`, [a2.token, DEV_B], 'DEVICE_MISMATCH', 'token copied to another phone -> rejected');
let res = await up(a2.token, DEV_A, [
  { day: yday, screen_time_sec: 18000, unlocks: 90, night_sec: 1200, apps },
  { day: today, screen_time_sec: 3600, unlocks: 20, night_sec: 0, apps: apps.slice(0, 3) },
  { day: '2020-01-01', screen_time_sec: 100 },
  { day: today, screen_time_sec: 999999 }  // clamped to 86400
]);
ok(res.saved === 3, 'upload saves valid days, skips very old day: ' + JSON.stringify(res));
await asOwner();
let rows = (await db.query(`select day::text, screen_time_sec, unlocks, jsonb_array_length(apps) n from daily_usage where participant_id=$1 order by day`, [a2.participant_id])).rows;
ok(rows.length === 2 && rows[1].screen_time_sec === 86400, 'impossible value clamped to 24h');
ok(rows[0].n === 20, 'app list capped at 20');
await asRole('anon');
await up(a2.token, DEV_A, [{ day: yday, screen_time_sec: 500, unlocks: 1, apps: [] }]);
await asOwner();
rows = (await db.query(`select screen_time_sec from daily_usage where participant_id=$1 and day=$2`, [a2.participant_id, yday])).rows;
ok(rows[0].screen_time_sec === 18000, 'smaller recount (Android forgot history) does not overwrite');
await asRole('anon');
await up(b1.token, DEV_B, [{ day: today, screen_time_sec: 7200, unlocks: 50, night_sec: 300, apps: [] }]);
await up(c1.token, DEV_C, [{ day: today, screen_time_sec: 100, unlocks: 5, apps: [] }]);

console.log('Mentor access');
await asRole('authenticated', '11111111-1111-1111-1111-111111111111');
const g = (await db.query(`select id, name from groups`)).rows;
ok(g.length === 1 && g[0].name === 'Block A', 'mentor sees only own group');
const ov = (await db.query(`select * from mentor_overview($1, current_date, 14)`, [g[0].id])).rows;
ok(ov.length === 2 && ov.map(x => x.full_name).join(',') === 'Asha Kumari,Ravi Singh', 'overview lists the 2 participants of Block A');
ok(ov[0].today_sec === 86400 && ov[0].days_reported === 2 && ov[0].daily.length === 2, 'overview numbers correct');
const otherGroup = (await (async () => { await asOwner(); const x = (await db.query(`select id from groups where name='Block B'`)).rows[0].id; await asRole('authenticated', '11111111-1111-1111-1111-111111111111'); return x; })());
const ov2 = (await db.query(`select * from mentor_overview($1, current_date, 14)`, [otherGroup])).rows;
ok(ov2.length === 0, 'mentor cannot see another group even by asking for its id');
const det = (await db.query(`select * from mentor_participant_days($1, current_date, 30)`, [c1.participant_id])).rows;
ok(det.length === 0, 'mentor cannot read other group participant detail');
const det2 = (await db.query(`select * from mentor_participant_days($1, current_date, 30)`, [a2.participant_id])).rows;
ok(det2.length === 2, 'mentor reads own participant detail');
await expectError(`select * from device_secrets`, [], 'permission denied', 'mentor cannot read tokens');
await expectError(`update participants set full_name='x'`, [], 'permission denied', 'mentor cannot edit data');
await expectError(`insert into daily_usage(participant_id, day, screen_time_sec) values ($1, current_date, 1)`, [a2.participant_id], 'permission denied', 'mentor cannot fake usage');
await db.query(`delete from participants where id=$1`, [c1.participant_id]);
await db.query(`delete from participants where id=$1`, [b1.participant_id]);
await asOwner();
const left = (await db.query(`select count(*)::int n from participants`)).rows[0].n;
ok(left === 2, 'mentor can reset own participant (B removed) but not other group (Meera kept)');
await asRole('anon');
const b2 = await reg('BLOCKA25', 'Ravi Singh', 'CS102', DEV_B);
ok(b2.relinked === false, 'after reset, the phone can register again');

console.log('Not signed in');
await asRole('authenticated', '33333333-3333-3333-3333-333333333333');
ok((await db.query(`select * from participants`)).rows.length === 0, 'random signed-in user (not a mentor) sees nothing');
await asRole('anon');
await expectError(`select * from mentor_overview($1, current_date, 14)`, [g[0].id], 'permission denied', 'anon cannot call mentor functions');


// ===================== v2: timers, websites, SOS, status =====================
const M1 = '11111111-1111-1111-1111-111111111111', M2 = '22222222-2222-2222-2222-222222222222';
const groupA = g[0].id, groupB = otherGroup;
const up2 = (tok, dev, days, status) => db.query(`select public.upload_usage($1,$2,$3::jsonb,'2.0',$4::jsonb) as r`,
  [tok, dev, JSON.stringify(days), status ? JSON.stringify(status) : null]).then(x => x.rows[0].r);

console.log('v2: old app versions keep working');
await asRole('anon');
let r1 = (await db.query(`select public.upload_usage(p_token => $1, p_device_hash => $2, p_days => $3::jsonb, p_app_version => '1.0') as r`,
  [a2.token, DEV_A, JSON.stringify([{ day: today, screen_time_sec: 86400, apps: [] }])])).rows[0].r;
ok(r1.saved === 1 && Array.isArray(r1.limits), 'v0.1 style upload (4 named args) still accepted');

console.log('v2: SOS phone number');
await asRole('authenticated', M1);
let sp = (await db.query(`select public.mentor_set_sos_phone($1, $2) as r`, [groupA, '+91 98765-43210'])).rows[0].r;
ok(sp.sos_phone === '+919876543210', 'mentor sets SOS number (spaces/dashes removed): ' + sp.sos_phone);
await expectError(`select public.mentor_set_sos_phone($1, 'call me')`, [groupA], 'BAD_PHONE', 'nonsense number rejected');
await expectError(`select public.mentor_set_sos_phone($1, '12')`, [groupA], 'BAD_PHONE', 'too-short number rejected');
ok((await db.query(`select public.mentor_set_sos_phone($1, '+91 98765-43210') as r`, [groupA])).rows[0].r.sos_phone === '+919876543210', 'number unchanged after rejected attempts');
await expectError(`select public.mentor_set_sos_phone($1, '+911234567')`, [groupB], 'NOT_ALLOWED', 'mentor cannot set another group\'s number');
await expectError(`update groups set sos_phone = '1'`, [], 'permission denied', 'mentor cannot edit groups directly');

console.log('v2: app timers');
const setLim = (grp, pid, pkg, name, min) => db.query(`select public.mentor_set_limit($1,$2,$3,$4,$5) as id`, [grp, pid, pkg, name, min]).then(x => x.rows[0].id);
const gLim = await setLim(groupA, null, 'com.instagram.android', 'Instagram', 60);
await setLim(groupA, null, 'com.google.android.youtube', 'YouTube', 90);
const pLim = await setLim(groupA, a2.participant_id, 'com.instagram.android', 'Instagram', 30);
const gLim2 = await setLim(groupA, null, 'com.instagram.android', 'Instagram', 45);
ok(gLim2 === gLim, 'changing a group timer updates it instead of duplicating');
await expectError(`select public.mentor_set_limit($1,null,'not a package','X',10)`, [groupA], 'BAD_APP', 'invalid app id rejected');
await expectError(`select public.mentor_set_limit($1,null,'com.x.y','X',0)`, [groupA], 'BAD_MINUTES', '0 minutes rejected');
await expectError(`select public.mentor_set_limit($1,null,'com.x.y','X',2000)`, [groupA], 'BAD_MINUTES', 'more than 24 h rejected');
await expectError(`select public.mentor_set_limit($1,null,'com.x.y','X',10)`, [groupB], 'NOT_ALLOWED', 'cannot set timer for another group');
await expectError(`select public.mentor_set_limit($1,$2,'com.x.y','X',10)`, [groupA, c1.participant_id], 'NOT_ALLOWED', 'cannot set timer on a student from another group');
await expectError(`insert into app_limits(group_id,pkg,minutes) values ($1,'com.a.b',5)`, [groupA], 'permission denied', 'mentor cannot insert timers directly');
ok((await db.query(`select count(*)::int n from app_limits`)).rows[0].n === 3, 'mentor reads own group timers (3)');
await asRole('authenticated', M2);
ok((await db.query(`select count(*)::int n from app_limits`)).rows[0].n === 0, 'other mentor sees none of them');
await expectError(`select public.mentor_remove_limit($1)`, [pLim], 'NOT_ALLOWED', 'other mentor cannot remove a timer');

await asRole('anon');
let ra = await up2(a2.token, DEV_A, [], { usage_access: true, watcher: true, notifications: false });
const lim = Object.fromEntries(ra.limits.map(l => [l.pkg, l.minutes]));
ok(lim['com.instagram.android'] === 30 && lim['com.google.android.youtube'] === 90 && ra.limits.length === 2,
   'phone gets merged timers: personal 30 beats group 45; group YouTube 90');
ok(ra.sos_phone === '+919876543210' && ra.group_name === 'Block A', 'phone gets SOS number and group name');
let rb = await up2(b2.token, DEV_B, [], null);
ok(rb.limits.find(l => l.pkg === 'com.instagram.android').minutes === 45, 'other student gets the group timer (45)');
let rc = await up2(c1.token, DEV_C, [], null);
ok(rc.limits.length === 0 && rc.sos_phone === null, 'student in another group gets no timers / number');
await asRole('authenticated', M1);
await db.query(`select public.mentor_remove_limit($1)`, [pLim]);
await asRole('anon');
ra = await up2(a2.token, DEV_A, [], null);
ok(ra.limits.find(l => l.pkg === 'com.instagram.android').minutes === 45, 'after removing personal timer, group timer applies');

console.log('v2: websites');
const sites = Array.from({ length: 14 }, (_, i) => ({ domain: (i === 0 ? 'YouTube.com' : `site${i}.example`), sec: 1000 - i }));
await up2(a2.token, DEV_A, [{ day: yday, screen_time_sec: 20000, apps: [], sites }], null);
await asOwner();
let row = (await db.query(`select sites from daily_usage where participant_id=$1 and day=$2`, [a2.participant_id, yday])).rows[0];
ok(row.sites.length === 10 && row.sites[0].domain === 'youtube.com', 'sites stored: top 10, lowercased');
await asRole('anon');
await up2(a2.token, DEV_A, [{ day: yday, screen_time_sec: 20001, apps: [] }], null);
await asOwner();
row = (await db.query(`select sites from daily_usage where participant_id=$1 and day=$2`, [a2.participant_id, yday])).rows[0];
ok(row.sites.length === 10, 'an upload without websites keeps the websites already saved');

console.log('v2: status + stop sharing');
await asRole('anon');
await up2(a2.token, DEV_A, [], { usage_access: true, watcher: false, notifications: true });
await db.query(`select public.report_stopped($1,$2)`, [b2.token, DEV_B]);
await expectError(`select public.report_stopped($1,$2)`, [b2.token, DEV_A], 'DEVICE_MISMATCH', 'stop report must come from the same phone');
await asRole('authenticated', M1);
let ovA = Object.fromEntries((await db.query(`select * from mentor_overview($1, current_date, 30)`, [groupA])).rows.map(x => [x.roll_no, x]));
ok(ovA.CS101.watcher_on === false && ovA.CS101.usage_access_on === true && ovA.CS101.notifications_on === true, 'overview shows phone status (timers/websites off)');
ok(ovA.CS102.stopped_at !== null, 'overview shows that a student stopped sharing');
await asRole('anon');
await up2(b2.token, DEV_B, [], null);
await asRole('authenticated', M1);
ovA = Object.fromEntries((await db.query(`select * from mentor_overview($1, current_date, 30)`, [groupA])).rows.map(x => [x.roll_no, x]));
ok(ovA.CS102.stopped_at === null, 'a later upload clears "stopped"');

console.log('v2: SOS');
await asRole('anon');
const sos1 = (await db.query(`select public.send_sos($1,$2,$3,$4,$5,$6) as r`, [a2.token, DEV_A, 12.9716, 77.5946, 25, 30])).rows[0].r;
ok(sos1.sos_id && sos1.sos_phone === '+919876543210', 'SOS sent with location; phone gets the number to call');
const sos2 = (await db.query(`select public.send_sos($1,$2) as r`, [b2.token, DEV_B])).rows[0].r;
ok(!!sos2.sos_id, 'SOS without location is accepted');
await expectError(`select public.send_sos($1,$2)`, ['nope', DEV_A], 'INVALID_TOKEN', 'SOS needs a valid phone token');
await db.query(`select public.update_sos_location($1,$2,$3,$4,$5,$6)`, [b2.token, DEV_B, sos2.sos_id, 28.6139, 77.2090, 15]);
let upd = (await db.query(`select public.update_sos_location($1,$2,$3,1,1,1) as r`, [a2.token, DEV_A, sos2.sos_id])).rows[0].r;
ok(upd.updated === 0, 'a student cannot change another student\'s SOS');
await expectError(`select * from sos_events`, [], 'permission denied', 'phones cannot read SOS table');
let st = (await db.query(`select public.sos_status($1,$2,$3) as r`, [a2.token, DEV_A, sos1.sos_id])).rows[0].r;
ok(st.acknowledged === false, 'phone sees "not seen yet"');
for (let i = 0; i < 4; i++) await db.query(`select public.send_sos($1,$2)`, [a2.token, DEV_A]);
await expectError(`select public.send_sos($1,$2)`, [a2.token, DEV_A], 'SOS_RATE_LIMIT', '6th SOS within 10 minutes is refused (anti-spam)');

await asRole('authenticated', M2);
ok((await db.query(`select * from mentor_sos($1, 30)`, [groupA])).rows.length === 0, 'other mentor sees no SOS from this group');
await expectError(`select public.mentor_ack_sos($1)`, [sos1.sos_id], 'NOT_ALLOWED', 'other mentor cannot acknowledge');
await asRole('authenticated', M1);
const sosList = (await db.query(`select * from mentor_sos($1, 30)`, [groupA])).rows;
ok(sosList.length === 6, 'mentor sees all 6 SOS presses of the group');
const s2row = sosList.find(x => x.id === sos2.sos_id);
ok(Math.abs(s2row.lat - 28.6139) < 1e-6 && s2row.full_name === 'Ravi Singh', 'updated location + name shown to mentor');
await db.query(`select public.mentor_ack_sos($1)`, [sos1.sos_id]);
await asRole('anon');
st = (await db.query(`select public.sos_status($1,$2,$3) as r`, [a2.token, DEV_A, sos1.sos_id])).rows[0].r;
ok(st.acknowledged === true, 'phone sees "your mentor has seen your alert"');

console.log('v2: reset removes everything of that student');
await asRole('authenticated', M1);
await db.query(`delete from participants where id = $1`, [b2.participant_id]);
await asOwner();
ok((await db.query(`select count(*)::int n from sos_events where participant_id=$1`, [b2.participant_id])).rows[0].n === 0, 'SOS history deleted with the student');

console.log('v2.1: group codes cannot be guessed');
await asRole('anon');
const fromIp = (ip) => db.exec(`select set_config('request.headers', '${JSON.stringify({ 'x-forwarded-for': ip + ', 10.0.0.1' })}', false)`);
await fromIp('203.0.113.7');
for (let i = 0; i < 10; i++) {
  const r = (await db.query(`select public.register_device($1,'Guesser','G1',$2) as r`, ['GUESS' + i, H('9')])).rows[0].r;
  if (r.error !== 'INVALID_CODE') ok(false, 'guess should be INVALID_CODE');
}
await expectError(`select public.register_device('BLOCKA25','Guesser','G1',$1)`, [H('9')], 'TOO_MANY_ATTEMPTS', '11th try from the same address is paused, even with the right code');
await fromIp('198.51.100.9');
const okReg = (await db.query(`select public.register_device('BLOCKA25','Other Person','G2',$1) as r`, [H('8')])).rows[0].r;
ok(!!okReg.token, 'a different address is not affected');
await expectError(`select * from register_attempts`, [], 'permission denied', 'phones cannot read the attempt log');
await asRole('authenticated', M1);
await expectError(`select * from register_attempts`, [], 'permission denied', 'mentors cannot read the attempt log');
await asOwner();
ok((await db.query(`select count(*)::int n from register_attempts where ip = '203.0.113.7'`)).rows[0].n === 10, 'failed guesses are recorded (10), first address only');
await db.exec(`select set_config('request.headers', '', false)`);
await asRole('anon');
ok(!!(await db.query(`select public.register_device('BLOCKA25','No Header','G3',$1) as r`, [H('7')])).rows[0].r.token, 'works without address headers (e.g. SQL Editor)');
await asRole('anon');
await expectError(`select public._client_ip()`, [], 'permission denied', 'phones cannot call internal helpers');

// ===================== Upgrade from v1 =====================
console.log('Upgrade: v1 database -> v2 keeps data');
const v1Path = new URL('./fixtures/schema_v1.sql', import.meta.url);
if (fs.existsSync(v1Path)) {
  const db2 = new PGlite();
  await db2.exec(`create role anon nologin; create role authenticated nologin;
    grant usage on schema public to anon, authenticated;
    alter default privileges in schema public grant all on tables to anon, authenticated;
    alter default privileges in schema public grant execute on functions to anon, authenticated;
    create schema auth; grant usage on schema auth to anon, authenticated;
    create table auth.users (id uuid primary key, email text);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.sub', true), '')::uuid $$;
    grant execute on function auth.uid() to anon, authenticated;
    insert into auth.users values ('${M1}', 'mentor@college.edu');`);
  await db2.exec(fs.readFileSync(v1Path, 'utf8'));
  await db2.query(`select public.setup_group('Old', 'OLD1', 'mentor@college.edu')`);
  const old = (await db2.query(`select public.register_device('OLD1','Old Student','R9',$1) as r`, [DEV_A])).rows[0].r;
  await db2.query(`select public.upload_usage($1,$2,$3::jsonb,'1.0')`, [old.token, DEV_A, JSON.stringify([{ day: today, screen_time_sec: 4321, apps: [{ pkg: 'a.b', name: 'AB', sec: 4000, opens: 3 }] }])]);
  await db2.exec(schema);
  await db2.exec(schema);
  const kept = (await db2.query(`select screen_time_sec, sites from daily_usage`)).rows;
  ok(kept.length === 1 && kept[0].screen_time_sec === 4321 && Array.isArray(kept[0].sites), 'existing reports kept; new websites column added');
  const after = (await db2.query(`select public.upload_usage(p_token => $1, p_device_hash => $2, p_days => '[]'::jsonb, p_app_version => '1.0') as r`, [old.token, DEV_A])).rows[0].r;
  ok(after.saved === 0 && after.group_name === 'Old', 'already-installed v0.1 phones keep working after the upgrade (same token)');
  const fns = (await db2.query(`select count(*)::int n from pg_proc where proname = 'upload_usage'`)).rows[0].n;
  ok(fns === 1, 'only one upload_usage function exists (old one replaced)');
} else {
  console.log('  (skipped: fixtures/schema_v1.sql not found)');
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
