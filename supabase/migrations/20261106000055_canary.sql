-- Canary family (docs/canary.md): a dummy family whose "parent" is a spare WhatsApp number that answers its own reminders
-- automatically, so the admin Monitor proves every 4 hours that the whole chain works (scheduler -> gateway send -> phone ->
-- auto-reply -> gateway webhook -> backend marks the dose taken). On 2026-11-05 replies were silently lost for 12 hours.
--
--   care_recipients.is_canary   the canary "parent" (Test Papa)
--   profiles.is_canary          the canary caregiver (Chhaon Canary, a dummy account nobody can log in to)
--
-- What this migration does besides the two flags:
--   1. No notification is ever created for or about the canary (a missed canary dose must alert NOBODY: the Monitor's
--      "Canary" check is the only place it shows). A trigger drops such rows, whichever code path inserts them.
--   2. admin_stats() (pilot numbers) leaves the canary out.
--   3. canary_setup(phone) / canary_remove(): create or delete the whole canary family in one call (used by
--      supabase/maintenance/canary_setup.sql and canary_remove.sql). Database owner only: no grants to anon / authenticated.
--
-- Sydney: paste into the SQL Editor BEFORE deploying the backend that reads is_canary. Safe to run twice.

alter table public.care_recipients add column if not exists is_canary boolean not null default false;
alter table public.profiles add column if not exists is_canary boolean not null default false;
create index if not exists care_recipients_canary_idx on public.care_recipients (id) where is_canary;

comment on column public.care_recipients.is_canary is
  'The Monitor''s canary "parent" (docs/canary.md): left out of stats, alerts and summaries; always a text reminder.';
comment on column public.profiles.is_canary is
  'The canary family''s dummy caregiver (docs/canary.md): no login, no alerts, left out of stats.';

-- ---------------------------------------------------------------- 1. no notification for or about the canary
create or replace function public.drop_canary_notifications() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if exists (select 1 from public.profiles p where p.id = new.user_id and p.is_canary)
     or (new.payload ? 'recipient_id'
         and exists (select 1 from public.care_recipients r where r.is_canary and r.id::text = new.payload ->> 'recipient_id')) then
    return null;   -- silently not stored: nobody is alerted, nothing is sent on WhatsApp
  end if;
  return new;
end $$;
revoke execute on function public.drop_canary_notifications() from public, anon, authenticated;

drop trigger if exists notifications_drop_canary on public.notifications;
create trigger notifications_drop_canary before insert on public.notifications
  for each row execute function public.drop_canary_notifications();

-- ---------------------------------------------------------------- 2. pilot stats without the canary
-- Same as migration 0032 (escalation), with the canary caregiver, parent and doses left out.
create or replace function public.admin_stats(p_days integer default 14) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_days integer := least(greatest(coalesce(p_days, 14), 1), 90);
  v_today date := (now() at time zone 'Asia/Kolkata')::date;
  v_result jsonb;
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;

  with seen as (   -- when each caregiver last used the app: last login or last time the phone refreshed the session
    select p.id, p.created_at, p.plan, p.trial_ends_at,
           greatest(u.last_sign_in_at, (select max(s.refreshed_at) from auth.sessions s where s.user_id = p.id)) as last_seen
      from public.profiles p join auth.users u on u.id = p.id
     where not p.is_canary
  ),
  people as (select * from public.care_recipients where not is_canary),
  doses as (
    select de.* from public.dose_events de
     where not exists (select 1 from public.medicine_schedules s join public.medicines m on m.id = s.medicine_id
                         join public.care_recipients r on r.id = m.recipient_id
                        where s.id = de.schedule_id and r.is_canary)
  )
  select jsonb_build_object(
    'generated_at', now(),
    'caregivers', jsonb_build_object(
      'total', (select count(*) from seen),
      'new_7d', (select count(*) from seen where created_at > now() - interval '7 days'),
      'active_7d', (select count(*) from seen where last_seen > now() - interval '7 days'),
      'joined_4w_ago', (select count(*) from seen where created_at <= now() - interval '28 days'),
      'still_active_of_those', (select count(*) from seen where created_at <= now() - interval '28 days' and last_seen > now() - interval '7 days')
    ),
    'parents', jsonb_build_object(
      'total', (select count(*) from people),
      'agreed', (select count(*) from people where consent_at is not null and opted_out_at is null),
      'waiting', (select count(*) from people where consent_at is null and opted_out_at is null and whatsapp_phone is not null),
      'stopped', (select count(*) from people where opted_out_at is not null),
      'paused_by_plan', (select count(*) from public.paused_recipients())
    ),
    'medicines', jsonb_build_object(
      'active', (select count(*) from public.medicines m where m.active and m.recipient_id in (select id from people)),
      'paused_by_plan', (select count(*) from public.paused_medicines())
    ),
    'reminders_7d', jsonb_build_object(
      'sent', (select count(*) from doses where sent_at > now() - interval '7 days'),
      'taken', (select count(*) from doses where sent_at > now() - interval '7 days' and status in ('taken', 'taken_late')),
      'missed', (select count(*) from doses where sent_at > now() - interval '7 days' and status = 'missed')
    ),
    'checkins_7d', jsonb_build_object(
      'sent', (select count(*) from public.checkins c where c.sent_at > now() - interval '7 days' and c.recipient_id in (select id from people)),
      'replied', (select count(*) from public.checkins c where c.sent_at > now() - interval '7 days' and c.status = 'replied' and c.recipient_id in (select id from people)),
      'missed', (select count(*) from public.checkins c where c.sent_at > now() - interval '7 days' and c.status = 'missed' and c.recipient_id in (select id from people))
    ),
    'whatsapp_7d', jsonb_build_object(   -- messages we sent: Utility (reminders, check-ins, alerts, appointment reminders) and Marketing (consent / opt-in requests)
      'utility', (select count(*) from doses where sent_at > now() - interval '7 days')
               + (select count(*) from public.checkins c where c.sent_at > now() - interval '7 days' and c.recipient_id in (select id from people))
               + (select count(*) from public.notifications where whatsapp_sent_at > now() - interval '7 days')
               + (select count(*) from public.appointments where reminded_day_before_at > now() - interval '7 days')
               + (select count(*) from public.appointments where reminded_morning_at > now() - interval '7 days'),
      'marketing', (select count(*) from public.consent_requests where requested_at > now() - interval '7 days')
    ),
    'plans', jsonb_build_object(
      'by_plan', coalesce((select jsonb_object_agg(plan, n) from (select plan, count(*) as n from seen group by plan) x), '{}'::jsonb),
      'on_trial', (select count(*) from seen where plan = 'free' and trial_ends_at > now()),
      'trial_ending_7d', (select count(*) from seen where plan = 'free' and trial_ends_at > now() and trial_ends_at <= now() + interval '7 days'),
      'trial_ended_free', (select count(*) from seen where plan = 'free' and trial_ends_at <= now())
    ),
    'family', jsonb_build_object(
      'members_joined', (select count(*) from public.family_invites where accepted_at is not null),
      'circles_with_more_than_one', (select count(*) from (select circle_id from public.care_circle_members group by circle_id having count(*) > 1) c)
    ),
    'daily', coalesce((
      select jsonb_agg(jsonb_build_object(
        'date', d.day, 'reminders_sent', coalesce(r.sent, 0), 'reminders_taken', coalesce(r.taken, 0), 'new_caregivers', coalesce(n.new, 0)) order by d.day)
      from generate_series(v_today - (v_days - 1), v_today, interval '1 day') as g(day_ts)
      cross join lateral (select g.day_ts::date as day) d
      left join (select (sent_at at time zone 'Asia/Kolkata')::date as day, count(*) as sent, count(*) filter (where status in ('taken', 'taken_late')) as taken
                   from doses where sent_at is not null group by 1) r on r.day = d.day
      left join (select (created_at at time zone 'Asia/Kolkata')::date as day, count(*) as new from public.profiles where not is_canary group by 1) n on n.day = d.day
    ), '[]'::jsonb)
  ) into v_result;
  return v_result;
end $$;
revoke execute on function public.admin_stats(integer) from public, anon;
grant execute on function public.admin_stats(integer) to authenticated;

-- ---------------------------------------------------------------- 3. create / remove the canary family
-- Fixed ids, so running the setup again updates the same rows instead of making a second family.
--   caregiver  cacacaca-0000-4000-8000-000000000001     circle   ...0002     person   ...0003     medicine ...0004
create or replace function public.canary_setup(p_phone text) returns uuid
language plpgsql set search_path = '' as $$
declare
  c_user      constant uuid := 'cacacaca-0000-4000-8000-000000000001';
  c_circle    constant uuid := 'cacacaca-0000-4000-8000-000000000002';
  c_recipient constant uuid := 'cacacaca-0000-4000-8000-000000000003';
  c_medicine  constant uuid := 'cacacaca-0000-4000-8000-000000000004';
  v_phone text := btrim(coalesce(p_phone, ''));
  v_today date := (now() at time zone 'Asia/Kolkata')::date;
  v_time time;
begin
  if v_phone !~ '^\+[1-9][0-9]{7,14}$' then
    raise exception 'canary_setup: give the spare SIM''s WhatsApp number in E.164, like +9199999xxxxx (got %)', v_phone;
  end if;
  if exists (select 1 from public.care_recipients where whatsapp_phone = v_phone and not is_canary) then
    raise exception 'canary_setup: this number already belongs to a real person cared for; use the spare SIM''s number';
  end if;
  if exists (select 1 from public.profiles where (alert_phone = v_phone or phone = v_phone) and not is_canary) then
    raise exception 'canary_setup: this number belongs to a caregiver account; use the spare SIM''s number';
  end if;
  if exists (select 1 from public.phone_opt_outs where phone = v_phone) then
    raise exception 'canary_setup: this number said STOP (phone_opt_outs); remove that row first if it really is the spare SIM';
  end if;

  -- the dummy caregiver. Older databases still tie profiles to Supabase auth.users: then a minimal auth row comes first
  -- (no password, nobody can log in with it).
  if exists (select 1 from pg_catalog.pg_constraint where conname = 'profiles_id_fkey' and conrelid = 'public.profiles'::regclass) then
    insert into auth.users (id, email, instance_id, aud, role)
    values (c_user, 'canary@chhaon.invalid', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated')
    on conflict (id) do nothing;
  end if;
  insert into public.profiles (id, name, email, timezone, language, plan, is_canary)
  values (c_user, 'Chhaon Canary', 'canary@chhaon.invalid', 'Asia/Kolkata', 'en', 'family', true)
  on conflict (id) do update set name = 'Chhaon Canary', email = 'canary@chhaon.invalid', plan = 'family', is_canary = true,
    phone = null, alert_phone = null, alert_requested_at = null, alert_confirmed_at = null, alert_consent_at = null;

  insert into public.care_circles (id, created_by) values (c_circle, c_user) on conflict (id) do nothing;   -- trigger adds the owner
  insert into public.care_circle_members (circle_id, user_id, role) values (c_circle, c_user, 'owner') on conflict do nothing;

  insert into public.care_recipients (id, circle_id, name, call_name, relation, language, whatsapp_phone, timezone,
                                      checkin_enabled, reminder_to, is_canary)
  values (c_recipient, c_circle, 'Test Papa', 'Test Papa', 'father', 'en', v_phone, 'Asia/Kolkata', false, 'person', true)
  on conflict (id) do update set name = 'Test Papa', call_name = 'Test Papa', language = 'en', whatsapp_phone = v_phone,
    timezone = 'Asia/Kolkata', checkin_enabled = false, reminder_to = 'person', reminder_name = null, reminder_language = null,
    care_type = 'older', is_canary = true;
  -- consent is already given (the spare SIM is ours); after the update above, since a changed number resets it
  update public.care_recipients set consent_at = coalesce(consent_at, now()), opted_out_at = null where id = c_recipient;

  insert into public.medicines (id, recipient_id, name, dosage, stock_count, refill_threshold, active)
  values (c_medicine, c_recipient, 'Test tablet', '1 tablet', null, null, true)
  on conflict (id) do update set name = 'Test tablet', dosage = '1 tablet', stock_count = null, refill_threshold = null,
    photo_url = null, pill_colour = null, pill_shape = null, active = true;

  -- every 4 hours on India time
  delete from public.medicine_schedules where medicine_id = c_medicine
     and time_of_day not in ('02:00', '06:00', '10:00', '14:00', '18:00', '22:00');
  foreach v_time in array array['02:00', '06:00', '10:00', '14:00', '18:00', '22:00']::time[] loop
    insert into public.medicine_schedules (medicine_id, time_of_day, start_date)
    values (c_medicine, v_time, v_today)
    on conflict (medicine_id, time_of_day) do nothing;
  end loop;

  return c_recipient;
end $$;
revoke execute on function public.canary_setup(text) from public, anon, authenticated;

create or replace function public.canary_remove() returns integer
language plpgsql set search_path = '' as $$
declare
  v_n integer;
begin
  select count(*) into v_n from public.care_recipients where is_canary;
  -- deleting the circle removes the person, the medicine, the schedules and every dose (on delete cascade)
  delete from public.care_circles where id in (select circle_id from public.care_recipients where is_canary)
     or created_by in (select id from public.profiles where is_canary);
  if to_regclass('auth.users') is not null then
    delete from auth.users where id in (select id from public.profiles where is_canary);
  end if;
  delete from public.profiles where is_canary;
  return v_n;
end $$;
revoke execute on function public.canary_remove() from public, anon, authenticated;
