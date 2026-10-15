-- Escalation ladder, phase 1 (no helper yet): a gentle nudge to the parent, then an alert to the family only when it matters.
-- Everything here is per parent and OFF until the family turns it on. The server also has its own on/off switch.

-- 1. Critical medicines: an unanswered reminder for one of these alerts the family on the first miss.
alter table public.medicines add column critical boolean not null default false;

-- 2. One policy per parent: when to nudge and alert (minutes after the reminder was sent), how many unanswered prompts in a row
--    before the family is alerted, and when a silent parent stops being nudged.
create table public.escalation_policies (
  recipient_id            uuid primary key references public.care_recipients (id) on delete cascade,
  enabled                 boolean not null default false,
  dose_nudge_min          integer not null default 15  check (dose_nudge_min between 5 and 120),
  dose_alert_min          integer not null default 45  check (dose_alert_min between 10 and 240),
  checkin_nudge_min       integer not null default 60  check (checkin_nudge_min between 10 and 240),
  checkin_alert_min       integer not null default 120 check (checkin_alert_min between 20 and 480),
  alert_after_unanswered  integer not null default 2   check (alert_after_unanswered between 1 and 3),
  silent_stop_days        integer not null default 3   check (silent_stop_days between 1 and 14),
  updated_at              timestamptz not null default now(),
  check (dose_nudge_min < dose_alert_min),
  check (checkin_nudge_min < checkin_alert_min)
);
alter table public.escalation_policies enable row level security;
create policy escalation_policies_all on public.escalation_policies for all to authenticated
using (public.can_access_recipient(recipient_id)) with check (public.can_access_recipient(recipient_id));
create trigger escalation_policies_updated_at before update on public.escalation_policies for each row execute function public.set_updated_at();

-- 3. The state of one unanswered prompt: a medicine time slot or a daily check-in. The backend writes it (guarded updates, so a step
--    can never run twice); the family can read it. slot_key: "dose:<time>" or "checkin:<date>".
create table public.escalations (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  kind          text not null check (kind in ('dose', 'checkin')),
  slot_key      text not null,
  base_at       timestamptz not null,           -- when the reminder / check-in was sent: every step is measured from here
  nudged_at     timestamptz,
  alerted_at    timestamptz,
  resolved_at   timestamptz,
  resolved_by   text check (resolved_by in ('parent', 'caregiver', 'silent')),
  handled_by    uuid references public.profiles (id) on delete set null,
  created_at    timestamptz not null default now(),
  unique (recipient_id, slot_key)
);
create index on public.escalations (recipient_id, base_at desc);
alter table public.escalations enable row level security;
create policy escalations_read on public.escalations for select to authenticated using (public.can_access_recipient(recipient_id));
-- no insert / update / delete policy: only the backend (service role) writes, and caregivers use handle_escalation()

-- 4. "I've handled it": any family member can stop the escalation of one prompt.
create function public.handle_escalation(p_escalation uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_recipient uuid; v_changed integer;
begin
  select recipient_id into v_recipient from public.escalations where id = p_escalation;
  if v_recipient is null or not public.can_access_recipient(v_recipient) then
    raise exception 'not found' using errcode = '42501';
  end if;
  update public.escalations set resolved_at = now(), resolved_by = 'caregiver', handled_by = (select auth.uid())
   where id = p_escalation and resolved_at is null;
  get diagnostics v_changed = row_count;
  return v_changed > 0;      -- false when it was already resolved (a second tap, or the parent answered first)
end $$;
revoke execute on function public.handle_escalation(uuid) from public, anon;
grant execute on function public.handle_escalation(uuid) to authenticated;

-- 5. Mark a medicine critical (or not): the family only.
create function public.set_medicine_critical(p_medicine uuid, p_critical boolean) returns void
language plpgsql security invoker set search_path = '' as $$
begin
  update public.medicines set critical = coalesce(p_critical, false) where id = p_medicine;
  if not found then raise exception 'not found' using errcode = '42501'; end if;
end $$;
revoke execute on function public.set_medicine_critical(uuid, boolean) from public, anon;
grant execute on function public.set_medicine_critical(uuid, boolean) to authenticated;

-- 6. "Taken all" now records a late confirmation as taken_late (a dose that had already been marked missed).
create or replace function public.confirm_dose_slot(p_dose_event uuid) returns table (medicine_id uuid, dosage text)
language sql security definer set search_path = '' as $$
  with anchor as (
    select m.recipient_id, de.scheduled_at
      from public.dose_events de
      join public.medicine_schedules s on s.id = de.schedule_id
      join public.medicines m on m.id = s.medicine_id
     where de.id = p_dose_event
  )
  update public.dose_events de
     set status = case when de.status = 'missed' then 'taken_late'::public.dose_status else 'taken'::public.dose_status end,
         responded_at = now()
    from public.medicine_schedules s, public.medicines m, anchor a
   where de.schedule_id = s.id and s.medicine_id = m.id
     and m.recipient_id = a.recipient_id and de.scheduled_at = a.scheduled_at
     and de.status in ('sent', 'missed')
  returning m.id, m.dosage
$$;

-- 7. The Pilot stats count a late confirmation as confirmed too.
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
      'total', (select count(*) from public.care_recipients),
      'agreed', (select count(*) from public.care_recipients where consent_at is not null and opted_out_at is null),
      'waiting', (select count(*) from public.care_recipients where consent_at is null and opted_out_at is null and whatsapp_phone is not null),
      'stopped', (select count(*) from public.care_recipients where opted_out_at is not null),
      'paused_by_plan', (select count(*) from public.paused_recipients())
    ),
    'medicines', jsonb_build_object(
      'active', (select count(*) from public.medicines where active),
      'paused_by_plan', (select count(*) from public.paused_medicines())
    ),
    'reminders_7d', jsonb_build_object(
      'sent', (select count(*) from public.dose_events where sent_at > now() - interval '7 days'),
      'taken', (select count(*) from public.dose_events where sent_at > now() - interval '7 days' and status in ('taken', 'taken_late')),
      'missed', (select count(*) from public.dose_events where sent_at > now() - interval '7 days' and status = 'missed')
    ),
    'checkins_7d', jsonb_build_object(
      'sent', (select count(*) from public.checkins where sent_at > now() - interval '7 days'),
      'replied', (select count(*) from public.checkins where sent_at > now() - interval '7 days' and status = 'replied'),
      'missed', (select count(*) from public.checkins where sent_at > now() - interval '7 days' and status = 'missed')
    ),
    'whatsapp_7d', jsonb_build_object(   -- messages we sent: Utility (reminders, check-ins, alerts, appointment reminders) and Marketing (consent / opt-in requests)
      'utility', (select count(*) from public.dose_events where sent_at > now() - interval '7 days')
               + (select count(*) from public.checkins where sent_at > now() - interval '7 days')
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
                   from public.dose_events where sent_at is not null group by 1) r on r.day = d.day
      left join (select (created_at at time zone 'Asia/Kolkata')::date as day, count(*) as new from public.profiles group by 1) n on n.day = d.day
    ), '[]'::jsonb)
  ) into v_result;
  return v_result;
end $$;
