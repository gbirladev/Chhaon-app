-- Pilot cap (docs/pilot-cap.md): Chhaon sends every reminder from ONE WhatsApp number through an unofficial gateway. What gets
-- such a number banned is messaging many NEW contacts in a short time and sudden jumps in volume. So while we are in pilot, the
-- number of people who get WhatsApp messages is capped, and new ones are let in a few per day. Sign-up stays open: a family over
-- the cap sets everything up and waits in a line; only the consent request (the first message to a new number) waits.
--
--   pilot_settings   one row: enabled, max_active (50), max_new_per_day (5). The owner changes it in the SQL Editor.
--   pilot_queue      one row per person cared for who asked to start WhatsApp while the pilot is on: queued_at, priority
--                    (moved to the front by the owner), admitted_at (let in; null = still waiting).
--
-- A "seat" is a WhatsApp NUMBER (not the canary) that has agreed, or was let in during the last 7 days and has not answered yet.
-- Two parents sharing one phone use one seat, and a person whose number already gets our messages is let in at once.
--
--   pilot_admit(recipient, user)   called by the backend just before a consent request: {status:'ok'} or
--                                  {status:'pilot_full', position}. Serialized: two taps can never take the same last seat.
--   pilot_position(recipient)      place in the line (null when not waiting) for the app's "You're #N in line".
--   pilot_summary()                the Monitor's Pilot card (numbers, the waiting line). No phone numbers.
--   pilot_move_up(recipient)       the owner's "Move to front".
-- All are for the backend (service role / database owner) only: no grants to anon / authenticated.
--
-- Sydney: paste into the SQL Editor AFTER migration 0055 (canary) and BEFORE deploying the backend that calls pilot_admit.
-- Safe to run twice.

-- ---------------------------------------------------------------- tables
create table if not exists public.pilot_settings (
  id              boolean primary key default true check (id),   -- exactly one row
  enabled         boolean not null default true,
  max_active      integer not null default 50 check (max_active >= 0),
  max_new_per_day integer not null default 5 check (max_new_per_day >= 0),
  updated_at      timestamptz not null default now()
);
insert into public.pilot_settings (id) values (true) on conflict (id) do nothing;
alter table public.pilot_settings enable row level security;
revoke all on table public.pilot_settings from anon, authenticated;
comment on table public.pilot_settings is
  'Pilot cap (docs/pilot-cap.md): how many WhatsApp numbers may get messages, and how many new ones per day. One row.';

create table if not exists public.pilot_queue (
  recipient_id uuid primary key references public.care_recipients (id) on delete cascade,
  requested_by uuid references public.profiles (id) on delete set null,
  queued_at    timestamptz not null default now(),
  priority     boolean not null default false,
  moved_up_at  timestamptz,
  admitted_at  timestamptz
);
create index if not exists pilot_queue_waiting_idx on public.pilot_queue (priority desc, moved_up_at desc, queued_at)
  where admitted_at is null;
create index if not exists pilot_queue_admitted_idx on public.pilot_queue (admitted_at) where admitted_at is not null;
alter table public.pilot_queue enable row level security;
revoke all on table public.pilot_queue from anon, authenticated;
comment on table public.pilot_queue is
  'Pilot cap line (docs/pilot-cap.md): who asked to start WhatsApp, in which order, and when they were let in.';

-- ---------------------------------------------------------------- the waiting line
-- Waiting = not let in yet, and still someone we could message: has a number, has not agreed, has not said STOP, not the canary.
create or replace function public.pilot_position(p_recipient uuid) returns integer
language sql stable security definer set search_path = '' as $$
  select w.pos::integer
    from (select q.recipient_id,
                 row_number() over (order by q.priority desc, q.moved_up_at desc nulls last, q.queued_at, q.recipient_id) pos
            from public.pilot_queue q
            join public.care_recipients r on r.id = q.recipient_id
           where q.admitted_at is null and r.whatsapp_phone is not null and r.consent_at is null
             and r.opted_out_at is null and not r.is_canary) w
   where w.recipient_id = p_recipient
     and coalesce((select s.enabled from public.pilot_settings s where s.id), false)
$$;

-- Seats in use: distinct numbers (not the canary, not stopped) that agreed, or were let in during the last 7 days.
create or replace function public.pilot_seats_used() returns integer
language sql stable security definer set search_path = '' as $$
  select count(distinct r.whatsapp_phone)::integer
    from public.care_recipients r
   where not r.is_canary and r.whatsapp_phone is not null and r.opted_out_at is null
     and (r.consent_at is not null
          or exists (select 1 from public.pilot_queue q
                      where q.recipient_id = r.id and q.admitted_at > now() - interval '7 days'))
$$;

-- ---------------------------------------------------------------- admission
create or replace function public.pilot_admit(p_recipient uuid, p_user uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_set  public.pilot_settings;
  v_rec  record;
  v_new  integer;
  v_room integer;
  v_pos  integer;
begin
  perform pg_advisory_xact_lock(hashtext('chhaon.pilot_admit'));   -- one decision at a time, across all families
  select * into v_set from public.pilot_settings where id;
  select r.id, r.whatsapp_phone, r.consent_at, r.is_canary into v_rec from public.care_recipients r where r.id = p_recipient;
  if not found then
    return jsonb_build_object('status', 'ok');   -- the backend answers "not found" itself
  end if;

  -- Not limited: the cap is off, the canary, or this person already agreed (a new number resets consent and comes back here).
  if v_set.id is null or not v_set.enabled or v_rec.is_canary or v_rec.consent_at is not null then
    update public.pilot_queue set admitted_at = now() where recipient_id = p_recipient and admitted_at is null;
    return jsonb_build_object('status', 'ok');
  end if;
  -- Let in once: keeps the place (asking again, a changed number, a failed send).
  if exists (select 1 from public.pilot_queue q where q.recipient_id = p_recipient and q.admitted_at is not null) then
    return jsonb_build_object('status', 'ok');
  end if;
  -- The number already gets our messages for someone else (two parents, one phone): no new contact, no new seat.
  if v_rec.whatsapp_phone is not null and exists (
       select 1 from public.care_recipients o
        where o.whatsapp_phone = v_rec.whatsapp_phone and o.id <> p_recipient
          and o.consent_at is not null and o.opted_out_at is null) then
    return jsonb_build_object('status', 'ok');
  end if;

  insert into public.pilot_queue (recipient_id, requested_by) values (p_recipient, p_user)
    on conflict (recipient_id) do nothing;

  v_new := (select count(*) from public.pilot_queue q where q.admitted_at > now() - interval '24 hours');
  v_room := least(v_set.max_active - public.pilot_seats_used(), v_set.max_new_per_day - v_new);
  v_pos := public.pilot_position(p_recipient);
  if v_pos is null then
    return jsonb_build_object('status', 'ok');   -- no number / stopped: the backend's own checks answer that
  end if;
  if v_room > 0 and v_pos <= v_room then
    update public.pilot_queue set admitted_at = now() where recipient_id = p_recipient;
    return jsonb_build_object('status', 'ok');
  end if;
  return jsonb_build_object('status', 'pilot_full', 'position', v_pos);
end $$;

-- ---------------------------------------------------------------- the owner's Monitor
create or replace function public.pilot_summary() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'enabled', coalesce(s.enabled, false),
    'max_active', s.max_active,
    'seats_used', public.pilot_seats_used(),
    'max_new_per_day', s.max_new_per_day,
    'new_24h', (select count(*) from public.pilot_queue q where q.admitted_at > now() - interval '24 hours'),
    'waiting', coalesce((
      select jsonb_agg(jsonb_build_object(
               'recipient_id', w.recipient_id, 'position', w.pos, 'queued_at', w.queued_at, 'priority', w.priority,
               'name', w.name, 'caregiver', w.caregiver) order by w.pos)
        from (select q.recipient_id, q.queued_at, q.priority, coalesce(nullif(r.call_name, ''), r.name) as name,
                     (select p.name from public.profiles p where p.id = q.requested_by) as caregiver,
                     row_number() over (order by q.priority desc, q.moved_up_at desc nulls last, q.queued_at, q.recipient_id) pos
                from public.pilot_queue q join public.care_recipients r on r.id = q.recipient_id
               where q.admitted_at is null and r.whatsapp_phone is not null and r.consent_at is null
                 and r.opted_out_at is null and not r.is_canary) w), '[]'::jsonb))
    from public.pilot_settings s where s.id
$$;

-- Move one waiting person to the front (behind nobody: the latest move wins). true when they were waiting.
create or replace function public.pilot_move_up(p_recipient uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  update public.pilot_queue set priority = true, moved_up_at = now()
   where recipient_id = p_recipient and admitted_at is null;
  return found;
end $$;

revoke execute on function public.pilot_position(uuid) from public, anon, authenticated;
revoke execute on function public.pilot_seats_used() from public, anon, authenticated;
revoke execute on function public.pilot_admit(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.pilot_summary() from public, anon, authenticated;
revoke execute on function public.pilot_move_up(uuid) from public, anon, authenticated;
grant execute on function public.pilot_position(uuid) to service_role;
grant execute on function public.pilot_seats_used() to service_role;
grant execute on function public.pilot_admit(uuid, uuid) to service_role;
grant execute on function public.pilot_summary() to service_role;
grant execute on function public.pilot_move_up(uuid) to service_role;
