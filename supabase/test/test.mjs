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
await expectError(`select public.register_device('WRONG','Asha','R1',$1)`, [DEV_A], 'INVALID_CODE', 'wrong join code rejected');
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

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
