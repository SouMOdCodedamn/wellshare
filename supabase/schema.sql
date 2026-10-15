-- =====================================================================
-- WellShare — database schema for Supabase (PostgreSQL 15+)
--
-- HOW TO USE: Supabase dashboard -> SQL Editor -> New query ->
-- paste this whole file -> Run. Safe to run again (it is idempotent).
--
-- Rules enforced here (not in the app, so they can't be bypassed):
--   * One phone  -> one participant   (participants.device_hash is UNIQUE)
--   * One participant -> one phone    (UNIQUE (group_id, roll_no))
--   * A second user profile on the same phone gets the SAME device hash
--     (the app uses the hardware DRM id), so it is rejected too.
--   * Phones can only call two functions: register_device, upload_usage.
--     They can never read anyone's data.
--   * Only signed-in mentors can read data, and only for their own group.
-- =====================================================================

-- ---------- Tables ----------------------------------------------------

create table if not exists public.groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  join_code   text not null unique,          -- students type this to join
  created_at  timestamptz not null default now()
);

create table if not exists public.mentors (
  user_id     uuid not null,                 -- = auth.users.id
  group_id    uuid not null references public.groups(id) on delete cascade,
  email       text,
  created_at  timestamptz not null default now(),
  primary key (user_id, group_id)
);

create table if not exists public.participants (
  id              uuid primary key default gen_random_uuid(),
  group_id        uuid not null references public.groups(id) on delete cascade,
  full_name       text not null,
  roll_no         text not null,
  device_hash     text not null unique,      -- one device = one participant
  id_source       text not null default 'drm',  -- 'drm' (strong) or 'android_id' (weaker)
  device_model    text,
  android_version text,
  app_version     text,
  created_at      timestamptz not null default now(),
  last_sync_at    timestamptz,
  unique (group_id, roll_no)                 -- one participant = one device
);

-- Secret upload tokens live in their own table with NO policies,
-- so they are never readable through the API (not even by mentors).
create table if not exists public.device_secrets (
  participant_id uuid primary key references public.participants(id) on delete cascade,
  token_hash     text not null unique,
  rotated_at     timestamptz not null default now()
);

create table if not exists public.daily_usage (
  participant_id  uuid not null references public.participants(id) on delete cascade,
  day             date not null,             -- the phone's local date
  screen_time_sec integer not null check (screen_time_sec between 0 and 86400),
  unlocks         integer check (unlocks between 0 and 10000),
  night_sec       integer not null default 0 check (night_sec between 0 and 18000), -- 00:00-05:00
  apps            jsonb not null default '[]'::jsonb,  -- [{"pkg","name","sec","opens"}], top 20
  updated_at      timestamptz not null default now(),
  primary key (participant_id, day)
);

create index if not exists participants_group_idx on public.participants(group_id);
create index if not exists daily_usage_day_idx on public.daily_usage(day);

-- ---------- Row Level Security ---------------------------------------

alter table public.groups         enable row level security;
alter table public.mentors        enable row level security;
alter table public.participants   enable row level security;
alter table public.device_secrets enable row level security;
alter table public.daily_usage    enable row level security;

-- Phones use the public "anon" key: give that role no direct table access.
revoke all on public.groups, public.mentors, public.participants,
              public.device_secrets, public.daily_usage from anon;
-- Mentors (role "authenticated") may only read, and remove a participant.
revoke all on public.groups, public.mentors, public.participants,
              public.device_secrets, public.daily_usage from authenticated;
grant select on public.groups, public.mentors, public.participants, public.daily_usage to authenticated;
grant delete on public.participants to authenticated;

create or replace function public.is_mentor_of(g uuid)
returns boolean
language sql stable security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1 from public.mentors m
    where m.user_id = auth.uid() and m.group_id = g
  );
$$;

drop policy if exists "mentor reads own groups"    on public.groups;
drop policy if exists "mentor reads own row"       on public.mentors;
drop policy if exists "mentor reads participants"  on public.participants;
drop policy if exists "mentor removes participant" on public.participants;
drop policy if exists "mentor reads usage"         on public.daily_usage;

create policy "mentor reads own groups" on public.groups
  for select to authenticated using (public.is_mentor_of(id));

create policy "mentor reads own row" on public.mentors
  for select to authenticated using (user_id = auth.uid());

create policy "mentor reads participants" on public.participants
  for select to authenticated using (public.is_mentor_of(group_id));

-- "Reset" a phone: deleting the participant frees both the phone and the
-- roll number so the student can register again. Their history is deleted too.
create policy "mentor removes participant" on public.participants
  for delete to authenticated using (public.is_mentor_of(group_id));

create policy "mentor reads usage" on public.daily_usage
  for select to authenticated using (
    exists (select 1 from public.participants p
            where p.id = participant_id and public.is_mentor_of(p.group_id))
  );

-- ---------- Helpers ---------------------------------------------------

create or replace function public._hash_token(t text)
returns text language sql immutable
set search_path = public, pg_temp
as $$ select encode(sha256(convert_to(t, 'UTF8')), 'hex') $$;

create or replace function public._new_token()
returns text language sql volatile
set search_path = public, pg_temp
as $$ select replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') $$;

-- ---------- Called by the phone app ----------------------------------

-- Registers this phone. Returns a secret token the phone keeps for uploads.
-- Re-installing the app on the same phone with the same roll number simply
-- re-links it (new token, same participant, history kept).
create or replace function public.register_device(
  p_join_code       text,
  p_full_name       text,
  p_roll_no         text,
  p_device_hash     text,
  p_id_source       text default 'drm',
  p_device_model    text default null,
  p_android_version text default null,
  p_app_version     text default null
)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_group    public.groups;
  v_existing public.participants;
  v_token    text := public._new_token();
  v_name     text := btrim(coalesce(p_full_name, ''));
  v_roll     text := upper(btrim(coalesce(p_roll_no, '')));
begin
  if p_device_hash is null or p_device_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'BAD_DEVICE_ID';
  end if;
  if length(v_name) < 2 or length(v_name) > 80 then
    raise exception 'BAD_NAME';
  end if;
  if length(v_roll) < 1 or length(v_roll) > 40 then
    raise exception 'BAD_ROLL';
  end if;

  select * into v_group from public.groups
   where upper(join_code) = upper(btrim(coalesce(p_join_code, '')));
  if not found then
    raise exception 'INVALID_CODE';
  end if;

  -- Is this phone already registered (any profile, any reinstall)?
  select * into v_existing from public.participants where device_hash = p_device_hash;
  if found then
    if v_existing.group_id = v_group.id and v_existing.roll_no = v_roll then
      update public.participants
         set app_version = coalesce(p_app_version, app_version),
             android_version = coalesce(p_android_version, android_version)
       where id = v_existing.id;
      insert into public.device_secrets (participant_id, token_hash)
        values (v_existing.id, public._hash_token(v_token))
        on conflict (participant_id) do update
          set token_hash = excluded.token_hash, rotated_at = now();
      return json_build_object('participant_id', v_existing.id, 'token', v_token,
                               'full_name', v_existing.full_name, 'roll_no', v_existing.roll_no,
                               'group_name', v_group.name, 'relinked', true);
    end if;
    raise exception 'DEVICE_ALREADY_REGISTERED';
  end if;

  -- Is this roll number already linked to a different phone?
  if exists (select 1 from public.participants where group_id = v_group.id and roll_no = v_roll) then
    raise exception 'ROLL_ALREADY_REGISTERED';
  end if;

  insert into public.participants (group_id, full_name, roll_no, device_hash, id_source,
                                   device_model, android_version, app_version)
  values (v_group.id, v_name, v_roll, p_device_hash,
          case when p_id_source = 'drm' then 'drm' else 'android_id' end,
          left(p_device_model, 80), left(p_android_version, 20), left(p_app_version, 20))
  returning * into v_existing;

  insert into public.device_secrets (participant_id, token_hash)
    values (v_existing.id, public._hash_token(v_token));

  return json_build_object('participant_id', v_existing.id, 'token', v_token,
                           'full_name', v_existing.full_name, 'roll_no', v_existing.roll_no,
                           'group_name', v_group.name, 'relinked', false);
end;
$$;

-- Uploads daily totals. p_days is a JSON array:
--   [{"day":"2026-10-14","screen_time_sec":12345,"unlocks":80,"night_sec":600,
--     "apps":[{"pkg":"com.instagram.android","name":"Instagram","sec":3600,"opens":25}]}]
create or replace function public.upload_usage(
  p_token       text,
  p_device_hash text,
  p_days        jsonb,
  p_app_version text default null
)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_pid   uuid;
  v_hash  text;
  d       jsonb;
  v_day   date;
  v_saved int := 0;
begin
  select s.participant_id, p.device_hash into v_pid, v_hash
    from public.device_secrets s
    join public.participants p on p.id = s.participant_id
   where s.token_hash = public._hash_token(coalesce(p_token, ''));
  if v_pid is null then
    raise exception 'INVALID_TOKEN';
  end if;
  -- The token only works from the phone it was issued to.
  if v_hash <> coalesce(p_device_hash, '') then
    raise exception 'DEVICE_MISMATCH';
  end if;
  if jsonb_typeof(p_days) <> 'array' or jsonb_array_length(p_days) > 31 then
    raise exception 'BAD_PAYLOAD';
  end if;

  for d in select * from jsonb_array_elements(p_days) loop
    v_day := (d->>'day')::date;
    -- accept only the last 31 days (plus one day of timezone slack)
    continue when v_day is null or v_day > current_date + 1 or v_day < current_date - 31;

    insert into public.daily_usage as u
      (participant_id, day, screen_time_sec, unlocks, night_sec, apps, updated_at)
    values (
      v_pid, v_day,
      least(greatest(coalesce((d->>'screen_time_sec')::int, 0), 0), 86400),
      case when d ? 'unlocks' and (d->>'unlocks') is not null
           then least(greatest((d->>'unlocks')::int, 0), 10000) end,
      least(greatest(coalesce((d->>'night_sec')::int, 0), 0), 18000),
      coalesce((select jsonb_agg(a) from (
                  select a from jsonb_array_elements(
                    case when jsonb_typeof(d->'apps') = 'array' then d->'apps' else '[]'::jsonb end) a
                  limit 20) t), '[]'::jsonb),
      now())
    on conflict (participant_id, day) do update
      set screen_time_sec = excluded.screen_time_sec,
          unlocks         = excluded.unlocks,
          night_sec       = excluded.night_sec,
          apps            = excluded.apps,
          updated_at      = now()
      -- Android keeps only ~1-2 weeks of history. Never let an older,
      -- partially-forgotten recount overwrite a bigger number we already have.
      where excluded.screen_time_sec >= u.screen_time_sec;

    v_saved := v_saved + 1;
  end loop;

  update public.participants
     set last_sync_at = now(),
         app_version  = coalesce(p_app_version, app_version)
   where id = v_pid;

  return json_build_object('saved', v_saved);
end;
$$;

-- ---------- Called by the mentor dashboard ---------------------------
-- These run with the mentor's own permissions, so RLS above applies.

-- One row per participant with summary numbers + a daily series.
create or replace function public.mentor_overview(p_group uuid, p_today date, p_days int default 14)
returns table (
  participant_id  uuid,
  full_name       text,
  roll_no         text,
  device_model    text,
  id_source       text,
  created_at      timestamptz,
  last_sync_at    timestamptz,
  days_reported   int,
  today_sec       int,
  avg7_sec        int,
  avg7_unlocks    int,
  avg7_night_sec  int,
  daily           jsonb
)
language sql stable security invoker
set search_path = public, pg_temp
as $$
  select p.id, p.full_name, p.roll_no, p.device_model, p.id_source, p.created_at, p.last_sync_at,
         (select count(*)::int from daily_usage u where u.participant_id = p.id
            and u.day > p_today - p_days and u.day <= p_today),
         (select u.screen_time_sec from daily_usage u where u.participant_id = p.id and u.day = p_today),
         (select avg(u.screen_time_sec)::int from daily_usage u where u.participant_id = p.id
            and u.day > p_today - 7 and u.day <= p_today),
         (select avg(u.unlocks)::int from daily_usage u where u.participant_id = p.id
            and u.day > p_today - 7 and u.day <= p_today),
         (select avg(u.night_sec)::int from daily_usage u where u.participant_id = p.id
            and u.day > p_today - 7 and u.day <= p_today),
         coalesce((select jsonb_agg(jsonb_build_object('day', u.day, 'sec', u.screen_time_sec,
                                                       'unlocks', u.unlocks, 'night', u.night_sec)
                                    order by u.day)
                     from daily_usage u where u.participant_id = p.id
                      and u.day > p_today - p_days and u.day <= p_today), '[]'::jsonb)
    from participants p
   where p.group_id = p_group
   order by p.full_name;
$$;

-- Full daily rows (with per-app breakdown) for one participant.
create or replace function public.mentor_participant_days(p_participant uuid, p_today date, p_days int default 30)
returns setof public.daily_usage
language sql stable security invoker
set search_path = public, pg_temp
as $$
  select * from daily_usage
   where participant_id = p_participant
     and day > p_today - p_days and day <= p_today
   order by day;
$$;

-- ---------- Admin helper (run in SQL Editor only) --------------------
-- 1) Create the mentor's login first: Authentication -> Users -> Add user.
-- 2) Then run:   select public.setup_group('Boys Hostel Block A', 'BLOCKA25', 'mentor@college.edu');
create or replace function public.setup_group(p_name text, p_join_code text, p_mentor_email text)
returns text
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_gid uuid;
  v_uid uuid;
begin
  select id into v_uid from auth.users where lower(email) = lower(btrim(p_mentor_email));
  if v_uid is null then
    raise exception 'No login found for %. Create it first in Authentication -> Users.', p_mentor_email;
  end if;

  insert into public.groups (name, join_code)
       values (btrim(p_name), upper(btrim(p_join_code)))
  on conflict (join_code) do update set name = excluded.name
  returning id into v_gid;

  insert into public.mentors (user_id, group_id, email)
       values (v_uid, v_gid, lower(btrim(p_mentor_email)))
  on conflict do nothing;

  return format('Group "%s" ready. Students join with code %s. Mentor: %s',
                p_name, upper(btrim(p_join_code)), p_mentor_email);
end;
$$;

-- ---------- Function permissions --------------------------------------
-- Supabase grants EXECUTE on new functions to everyone by default; tighten it.
revoke execute on function public.register_device(text,text,text,text,text,text,text,text) from public, anon, authenticated;
revoke execute on function public.upload_usage(text,text,jsonb,text)                         from public, anon, authenticated;
revoke execute on function public.mentor_overview(uuid,date,int)                             from public, anon, authenticated;
revoke execute on function public.mentor_participant_days(uuid,date,int)                     from public, anon, authenticated;
revoke execute on function public.setup_group(text,text,text)                                from public, anon, authenticated;
revoke execute on function public.is_mentor_of(uuid)                                         from public, anon, authenticated;
revoke execute on function public._hash_token(text)                                          from public, anon, authenticated;
revoke execute on function public._new_token()                                               from public, anon, authenticated;

grant execute on function public.register_device(text,text,text,text,text,text,text,text) to anon, authenticated;
grant execute on function public.upload_usage(text,text,jsonb,text)                         to anon, authenticated;
grant execute on function public.mentor_overview(uuid,date,int)                             to authenticated;
grant execute on function public.mentor_participant_days(uuid,date,int)                     to authenticated;
grant execute on function public.is_mentor_of(uuid)                                         to authenticated;
-- setup_group: no grants -> only the SQL Editor (database owner) can run it.
