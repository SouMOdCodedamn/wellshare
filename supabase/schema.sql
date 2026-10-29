-- =====================================================================
-- WellShare — database schema for Supabase (PostgreSQL 15+)        v2
--
-- HOW TO USE: Supabase dashboard -> SQL Editor -> New query ->
-- paste this whole file -> Run.
-- Safe to run again, and safe to run on top of v1: it upgrades in place
-- and keeps all existing students and reports.
--
-- Rules enforced here (not in the app, so they can't be bypassed):
--   * One phone  -> one participant   (participants.device_hash is UNIQUE)
--   * One participant -> one phone    (UNIQUE (group_id, roll_no))
--   * A second user profile on the same phone gets the SAME device hash
--     (the app uses the hardware DRM id), so it is rejected too.
--   * Phones can only call the phone functions below (register, upload,
--     SOS). They can never read anyone else's data.
--   * Only signed-in mentors can read data, and only for their own group.
--   * Mentors change things only through functions that check their group.
-- =====================================================================

-- ---------- Tables ----------------------------------------------------

create table if not exists public.groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  join_code   text not null unique,          -- students type this to join
  created_at  timestamptz not null default now()
);
alter table public.groups add column if not exists sos_phone text;  -- number the SOS button calls

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
-- v2: what the phone reports about itself on every upload
alter table public.participants add column if not exists usage_access_on  boolean;
alter table public.participants add column if not exists watcher_on       boolean;  -- Accessibility (timers + websites)
alter table public.participants add column if not exists notifications_on boolean;
alter table public.participants add column if not exists stopped_at       timestamptz; -- student tapped "Stop sharing"

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
-- v2: websites in Chrome-family browsers, top 10: [{"domain","sec"}]
alter table public.daily_usage add column if not exists sites jsonb not null default '[]'::jsonb;

-- v2: daily app timers set by mentors. participant_id NULL = whole group.
create table if not exists public.app_limits (
  id             uuid primary key default gen_random_uuid(),
  group_id       uuid not null references public.groups(id) on delete cascade,
  participant_id uuid references public.participants(id) on delete cascade,
  pkg            text not null check (length(pkg) between 3 and 200),
  app_name       text not null default '',
  minutes        integer not null check (minutes between 1 and 1440),
  created_by     uuid,
  updated_at     timestamptz not null default now()
);
create unique index if not exists app_limits_one_per_app
  on public.app_limits (group_id, coalesce(participant_id, '00000000-0000-0000-0000-000000000000'::uuid), pkg);

-- v2: SOS presses
create table if not exists public.sos_events (
  id              uuid primary key default gen_random_uuid(),
  participant_id  uuid not null references public.participants(id) on delete cascade,
  created_at      timestamptz not null default now(),
  lat             double precision check (lat between -90 and 90),
  lon             double precision check (lon between -180 and 180),
  accuracy_m      real,
  location_at     timestamptz,
  acknowledged_at timestamptz,
  acknowledged_by uuid
);

-- v2.1: failed registration attempts, to stop group codes being guessed
create table if not exists public.register_attempts (
  id  bigserial primary key,
  ip  text not null,
  at  timestamptz not null default now()
);
create index if not exists register_attempts_ip_idx on public.register_attempts(ip, at);

create index if not exists participants_group_idx on public.participants(group_id);
create index if not exists daily_usage_day_idx on public.daily_usage(day);
create index if not exists app_limits_group_idx on public.app_limits(group_id);
create index if not exists sos_events_participant_idx on public.sos_events(participant_id, created_at desc);

-- ---------- Row Level Security ---------------------------------------

alter table public.groups         enable row level security;
alter table public.mentors        enable row level security;
alter table public.participants   enable row level security;
alter table public.device_secrets enable row level security;
alter table public.daily_usage    enable row level security;
alter table public.app_limits     enable row level security;
alter table public.sos_events     enable row level security;
alter table public.register_attempts enable row level security;   -- no policies: never readable through the API

-- Phones use the public key ("anon"): give that role no direct table access.
revoke all on public.groups, public.mentors, public.participants, public.device_secrets,
              public.daily_usage, public.app_limits, public.sos_events, public.register_attempts from anon;
-- Mentors (role "authenticated") may only read, and remove a participant.
-- Every other change goes through the mentor_* functions below.
revoke all on public.groups, public.mentors, public.participants, public.device_secrets,
              public.daily_usage, public.app_limits, public.sos_events, public.register_attempts from authenticated;
grant select on public.groups, public.mentors, public.participants, public.daily_usage,
                public.app_limits, public.sos_events to authenticated;
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
drop policy if exists "mentor reads limits"        on public.app_limits;
drop policy if exists "mentor reads sos"           on public.sos_events;

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

create policy "mentor reads limits" on public.app_limits
  for select to authenticated using (public.is_mentor_of(group_id));

create policy "mentor reads sos" on public.sos_events
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

-- Finds the participant behind a phone's token. The token only works from
-- the phone it was issued to.
create or replace function public._phone_participant(p_token text, p_device_hash text)
returns uuid
language plpgsql stable security definer
set search_path = public, pg_temp
as $$
declare
  v_pid  uuid;
  v_hash text;
begin
  select s.participant_id, p.device_hash into v_pid, v_hash
    from public.device_secrets s
    join public.participants p on p.id = s.participant_id
   where s.token_hash = public._hash_token(coalesce(p_token, ''));
  if v_pid is null then
    raise exception 'INVALID_TOKEN';
  end if;
  if v_hash <> coalesce(p_device_hash, '') then
    raise exception 'DEVICE_MISMATCH';
  end if;
  return v_pid;
end;
$$;

-- The caller's IP address as seen by the API gateway (null in the SQL Editor).
create or replace function public._client_ip()
returns text
language sql stable
set search_path = public, pg_temp
as $$
  select nullif(btrim(coalesce(
           h->>'cf-connecting-ip',
           split_part(coalesce(h->>'x-forwarded-for', ''), ',', 1),
           h->>'x-real-ip', '')), '')
    from (select nullif(current_setting('request.headers', true), '')::json as h) t;
$$;

-- App timers that apply to one participant. A personal timer beats a group timer.
create or replace function public._limits_for(p_pid uuid)
returns jsonb
language sql stable security definer
set search_path = public, pg_temp
as $$
  select coalesce(jsonb_agg(jsonb_build_object('pkg', pkg, 'name', app_name, 'minutes', minutes)
                            order by app_name, pkg), '[]'::jsonb)
    from (
      select distinct on (l.pkg) l.pkg, l.app_name, l.minutes
        from public.app_limits l
        join public.participants p on p.id = p_pid
       where l.group_id = p.group_id
         and (l.participant_id = p_pid or l.participant_id is null)
       order by l.pkg, (l.participant_id is null)
    ) t;
$$;

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
  v_ip       text := public._client_ip();
begin
  -- Stop group codes being guessed: at most 10 wrong codes per network address per 15 minutes.
  if v_ip is not null and (select count(*) from public.register_attempts
                            where ip = v_ip and at > now() - interval '15 minutes') >= 10 then
    raise exception 'TOO_MANY_ATTEMPTS';
  end if;

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
    -- Returned (not raised) so the failed attempt is saved and counted.
    if v_ip is not null then
      delete from public.register_attempts where at < now() - interval '1 day';
      insert into public.register_attempts (ip) values (v_ip);
    end if;
    return json_build_object('error', 'INVALID_CODE');
  end if;

  -- A whole hostel registering at once is fine; hundreds of new phones in minutes is not.
  if (select count(*) from public.participants
       where group_id = v_group.id and created_at > now() - interval '10 minutes') >= 100 then
    raise exception 'TOO_MANY_ATTEMPTS';
  end if;

  -- Is this phone already registered (any profile, any reinstall)?
  select * into v_existing from public.participants where device_hash = p_device_hash;
  if found then
    if v_existing.group_id = v_group.id and v_existing.roll_no = v_roll then
      update public.participants
         set app_version = coalesce(p_app_version, app_version),
             android_version = coalesce(p_android_version, android_version),
             stopped_at = null
       where id = v_existing.id;
      insert into public.device_secrets (participant_id, token_hash)
        values (v_existing.id, public._hash_token(v_token))
        on conflict (participant_id) do update
          set token_hash = excluded.token_hash, rotated_at = now();
      return json_build_object('participant_id', v_existing.id, 'token', v_token,
                               'full_name', v_existing.full_name, 'roll_no', v_existing.roll_no,
                               'group_name', v_group.name, 'sos_phone', v_group.sos_phone,
                               'limits', public._limits_for(v_existing.id), 'relinked', true);
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
                           'group_name', v_group.name, 'sos_phone', v_group.sos_phone,
                           'limits', public._limits_for(v_existing.id), 'relinked', false);
end;
$$;

-- Uploads daily totals and returns this phone's settings (app timers, SOS number).
-- p_days is a JSON array:
--   [{"day":"2026-10-14","screen_time_sec":12345,"unlocks":80,"night_sec":600,
--     "apps":[{"pkg":"com.instagram.android","name":"Instagram","sec":3600,"opens":25}],
--     "sites":[{"domain":"youtube.com","sec":1200}]}]
-- p_status: {"usage_access":true,"watcher":true,"notifications":true}
drop function if exists public.upload_usage(text, text, jsonb, text);
create or replace function public.upload_usage(
  p_token       text,
  p_device_hash text,
  p_days        jsonb,
  p_app_version text default null,
  p_status      jsonb default null
)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_pid   uuid := public._phone_participant(p_token, p_device_hash);
  d       jsonb;
  v_day   date;
  v_saved int := 0;
  v_group public.groups;
begin
  if jsonb_typeof(p_days) <> 'array' or jsonb_array_length(p_days) > 31 then
    raise exception 'BAD_PAYLOAD';
  end if;

  for d in select * from jsonb_array_elements(p_days) loop
    v_day := (d->>'day')::date;
    -- accept only the last 31 days (plus one day of timezone slack)
    continue when v_day is null or v_day > current_date + 1 or v_day < current_date - 31;

    insert into public.daily_usage as u
      (participant_id, day, screen_time_sec, unlocks, night_sec, apps, sites, updated_at)
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
      coalesce((select jsonb_agg(jsonb_build_object(
                         'domain', left(lower(s->>'domain'), 120),
                         'sec', least(greatest(coalesce((s->>'sec')::int, 0), 0), 86400)))
                  from (select s from jsonb_array_elements(
                          case when jsonb_typeof(d->'sites') = 'array' then d->'sites' else '[]'::jsonb end) s
                        where coalesce(s->>'domain', '') <> ''
                        limit 10) t), '[]'::jsonb),
      now())
    on conflict (participant_id, day) do update
      set screen_time_sec = excluded.screen_time_sec,
          unlocks         = excluded.unlocks,
          night_sec       = excluded.night_sec,
          apps            = excluded.apps,
          sites           = case when jsonb_array_length(excluded.sites) > 0 then excluded.sites else u.sites end,
          updated_at      = now()
      -- Android keeps only ~1-2 weeks of history. Never let an older,
      -- partially-forgotten recount overwrite a bigger number we already have.
      where excluded.screen_time_sec >= u.screen_time_sec;

    v_saved := v_saved + 1;
  end loop;

  update public.participants
     set last_sync_at     = now(),
         app_version      = coalesce(left(p_app_version, 20), app_version),
         usage_access_on  = coalesce((p_status->>'usage_access')::boolean, usage_access_on),
         watcher_on       = coalesce((p_status->>'watcher')::boolean, watcher_on),
         notifications_on = coalesce((p_status->>'notifications')::boolean, notifications_on),
         stopped_at       = null
   where id = v_pid;

  select g.* into v_group from public.groups g
    join public.participants p on p.group_id = g.id where p.id = v_pid;

  return json_build_object('saved', v_saved,
                           'limits', public._limits_for(v_pid),
                           'sos_phone', v_group.sos_phone,
                           'group_name', v_group.name);
end;
$$;

-- SOS: the student pressed the emergency button. Location is optional.
create or replace function public.send_sos(
  p_token       text,
  p_device_hash text,
  p_lat         double precision default null,
  p_lon         double precision default null,
  p_accuracy    real default null,
  p_location_age_sec integer default null
)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_pid   uuid := public._phone_participant(p_token, p_device_hash);
  v_id    uuid;
  v_phone text;
  v_has_loc boolean := p_lat is not null and p_lon is not null
                       and p_lat between -90 and 90 and p_lon between -180 and 180;
begin
  if (select count(*) from public.sos_events
       where participant_id = v_pid and created_at > now() - interval '10 minutes') >= 5 then
    raise exception 'SOS_RATE_LIMIT';
  end if;

  insert into public.sos_events (participant_id, lat, lon, accuracy_m, location_at)
  values (v_pid,
          case when v_has_loc then p_lat end,
          case when v_has_loc then p_lon end,
          case when v_has_loc then p_accuracy end,
          case when v_has_loc then now() - make_interval(secs => greatest(coalesce(p_location_age_sec, 0), 0)) end)
  returning id into v_id;

  select g.sos_phone into v_phone from public.groups g
    join public.participants p on p.group_id = g.id where p.id = v_pid;

  return json_build_object('sos_id', v_id, 'sos_phone', v_phone);
end;
$$;

-- SOS: a fresher location arrived a few seconds after the alert was sent.
create or replace function public.update_sos_location(
  p_token       text,
  p_device_hash text,
  p_sos_id      uuid,
  p_lat         double precision,
  p_lon         double precision,
  p_accuracy    real default null
)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_pid uuid := public._phone_participant(p_token, p_device_hash);
  v_n   int;
begin
  if p_lat is null or p_lon is null or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    raise exception 'BAD_LOCATION';
  end if;
  update public.sos_events
     set lat = p_lat, lon = p_lon, accuracy_m = p_accuracy, location_at = now()
   where id = p_sos_id and participant_id = v_pid and created_at > now() - interval '1 hour';
  get diagnostics v_n = row_count;
  return json_build_object('updated', v_n);
end;
$$;

-- SOS: has a mentor seen it yet? (the phone shows "Your mentor has seen your alert")
create or replace function public.sos_status(p_token text, p_device_hash text, p_sos_id uuid)
returns json
language plpgsql stable security definer
set search_path = public, pg_temp
as $$
declare
  v_pid uuid := public._phone_participant(p_token, p_device_hash);
  v_ack timestamptz;
begin
  select acknowledged_at into v_ack from public.sos_events
   where id = p_sos_id and participant_id = v_pid;
  return json_build_object('acknowledged', v_ack is not null, 'acknowledged_at', v_ack);
end;
$$;

-- The student tapped "Stop sharing on this phone".
create or replace function public.report_stopped(p_token text, p_device_hash text)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_pid uuid := public._phone_participant(p_token, p_device_hash);
begin
  update public.participants set stopped_at = now() where id = v_pid;
  return json_build_object('ok', true);
end;
$$;

-- ---------- Called by the mentor dashboard ---------------------------

-- One row per participant with summary numbers + a daily series.
-- (dropped first because v2 returns more columns than v1)
drop function if exists public.mentor_overview(uuid, date, int);
create or replace function public.mentor_overview(p_group uuid, p_today date, p_days int default 14)
returns table (
  participant_id   uuid,
  full_name        text,
  roll_no          text,
  device_model     text,
  id_source        text,
  app_version      text,
  created_at       timestamptz,
  last_sync_at     timestamptz,
  usage_access_on  boolean,
  watcher_on       boolean,
  notifications_on boolean,
  stopped_at       timestamptz,
  days_reported    int,
  today_sec        int,
  avg7_sec         int,
  avg7_unlocks     int,
  avg7_night_sec   int,
  daily            jsonb
)
language sql stable security invoker
set search_path = public, pg_temp
as $$
  select p.id, p.full_name, p.roll_no, p.device_model, p.id_source, p.app_version, p.created_at, p.last_sync_at,
         p.usage_access_on, p.watcher_on, p.notifications_on, p.stopped_at,
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

-- Full daily rows (with per-app and per-website breakdown) for one participant.
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

-- SOS presses in a group, newest first.
create or replace function public.mentor_sos(p_group uuid, p_days int default 30)
returns table (
  id              uuid,
  participant_id  uuid,
  full_name       text,
  roll_no         text,
  created_at      timestamptz,
  lat             double precision,
  lon             double precision,
  accuracy_m      real,
  location_at     timestamptz,
  acknowledged_at timestamptz
)
language sql stable security invoker
set search_path = public, pg_temp
as $$
  select s.id, s.participant_id, p.full_name, p.roll_no, s.created_at, s.lat, s.lon,
         s.accuracy_m, s.location_at, s.acknowledged_at
    from sos_events s
    join participants p on p.id = s.participant_id
   where p.group_id = p_group
     and s.created_at > now() - make_interval(days => p_days)
   order by s.created_at desc
   limit 200;
$$;

create or replace function public.mentor_ack_sos(p_sos uuid)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_group uuid;
begin
  select p.group_id into v_group from public.sos_events s
    join public.participants p on p.id = s.participant_id where s.id = p_sos;
  if v_group is null or not public.is_mentor_of(v_group) then
    raise exception 'NOT_ALLOWED';
  end if;
  update public.sos_events
     set acknowledged_at = coalesce(acknowledged_at, now()),
         acknowledged_by = coalesce(acknowledged_by, auth.uid())
   where id = p_sos;
  return json_build_object('ok', true);
end;
$$;

-- Set or change a daily app timer. p_participant NULL = whole group.
create or replace function public.mentor_set_limit(
  p_group       uuid,
  p_participant uuid,
  p_pkg         text,
  p_name        text,
  p_minutes     integer
)
returns uuid
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_id  uuid;
  v_pkg text := lower(btrim(coalesce(p_pkg, '')));
begin
  if not public.is_mentor_of(p_group) then
    raise exception 'NOT_ALLOWED';
  end if;
  if p_participant is not null and not exists (
       select 1 from public.participants where id = p_participant and group_id = p_group) then
    raise exception 'NOT_ALLOWED';
  end if;
  if v_pkg !~ '^[a-z0-9_]+(\.[a-z0-9_]+)+$' then
    raise exception 'BAD_APP';
  end if;
  if p_minutes is null or p_minutes < 1 or p_minutes > 1440 then
    raise exception 'BAD_MINUTES';
  end if;

  update public.app_limits
     set minutes = p_minutes, app_name = left(coalesce(nullif(btrim(p_name), ''), app_name), 80),
         created_by = auth.uid(), updated_at = now()
   where group_id = p_group and pkg = v_pkg
     and participant_id is not distinct from p_participant
  returning id into v_id;

  if v_id is null then
    insert into public.app_limits (group_id, participant_id, pkg, app_name, minutes, created_by)
    values (p_group, p_participant, v_pkg, left(coalesce(btrim(p_name), ''), 80), p_minutes, auth.uid())
    returning id into v_id;
  end if;
  return v_id;
end;
$$;

create or replace function public.mentor_remove_limit(p_limit uuid)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  v_group uuid;
begin
  select group_id into v_group from public.app_limits where id = p_limit;
  if v_group is null or not public.is_mentor_of(v_group) then
    raise exception 'NOT_ALLOWED';
  end if;
  delete from public.app_limits where id = p_limit;
  return json_build_object('ok', true);
end;
$$;

-- The phone number the SOS button calls (empty = none).
create or replace function public.mentor_set_sos_phone(p_group uuid, p_phone text)
returns json
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
  -- allow spaces, dashes, dots and brackets as separators; anything else is an error
  v_phone text := nullif(regexp_replace(btrim(coalesce(p_phone, '')), '[\s().-]', '', 'g'), '');
begin
  if not public.is_mentor_of(p_group) then
    raise exception 'NOT_ALLOWED';
  end if;
  if v_phone is not null and v_phone !~ '^\+?[0-9]{6,15}$' then
    raise exception 'BAD_PHONE';
  end if;
  update public.groups set sos_phone = v_phone where id = p_group;
  return json_build_object('sos_phone', v_phone);
end;
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
revoke execute on function public.upload_usage(text,text,jsonb,text,jsonb)                  from public, anon, authenticated;
revoke execute on function public.send_sos(text,text,double precision,double precision,real,integer) from public, anon, authenticated;
revoke execute on function public.update_sos_location(text,text,uuid,double precision,double precision,real) from public, anon, authenticated;
revoke execute on function public.sos_status(text,text,uuid)                                 from public, anon, authenticated;
revoke execute on function public.report_stopped(text,text)                                 from public, anon, authenticated;
revoke execute on function public.mentor_overview(uuid,date,int)                             from public, anon, authenticated;
revoke execute on function public.mentor_participant_days(uuid,date,int)                     from public, anon, authenticated;
revoke execute on function public.mentor_sos(uuid,int)                                       from public, anon, authenticated;
revoke execute on function public.mentor_ack_sos(uuid)                                       from public, anon, authenticated;
revoke execute on function public.mentor_set_limit(uuid,uuid,text,text,integer)              from public, anon, authenticated;
revoke execute on function public.mentor_remove_limit(uuid)                                  from public, anon, authenticated;
revoke execute on function public.mentor_set_sos_phone(uuid,text)                            from public, anon, authenticated;
revoke execute on function public.setup_group(text,text,text)                                from public, anon, authenticated;
revoke execute on function public.is_mentor_of(uuid)                                         from public, anon, authenticated;
revoke execute on function public._hash_token(text)                                          from public, anon, authenticated;
revoke execute on function public._new_token()                                               from public, anon, authenticated;
revoke execute on function public._phone_participant(text,text)                              from public, anon, authenticated;
revoke execute on function public._limits_for(uuid)                                          from public, anon, authenticated;
revoke execute on function public._client_ip()                                               from public, anon, authenticated;

-- Phone functions
grant execute on function public.register_device(text,text,text,text,text,text,text,text) to anon, authenticated;
grant execute on function public.upload_usage(text,text,jsonb,text,jsonb)                  to anon, authenticated;
grant execute on function public.send_sos(text,text,double precision,double precision,real,integer) to anon, authenticated;
grant execute on function public.update_sos_location(text,text,uuid,double precision,double precision,real) to anon, authenticated;
grant execute on function public.sos_status(text,text,uuid)                                 to anon, authenticated;
grant execute on function public.report_stopped(text,text)                                 to anon, authenticated;
-- Mentor functions
grant execute on function public.mentor_overview(uuid,date,int)                             to authenticated;
grant execute on function public.mentor_participant_days(uuid,date,int)                     to authenticated;
grant execute on function public.mentor_sos(uuid,int)                                       to authenticated;
grant execute on function public.mentor_ack_sos(uuid)                                       to authenticated;
grant execute on function public.mentor_set_limit(uuid,uuid,text,text,integer)              to authenticated;
grant execute on function public.mentor_remove_limit(uuid)                                  to authenticated;
grant execute on function public.mentor_set_sos_phone(uuid,text)                            to authenticated;
grant execute on function public.is_mentor_of(uuid)                                         to authenticated;
-- setup_group: no grants -> only the SQL Editor (database owner) can run it.
