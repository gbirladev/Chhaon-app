-- Step 4: scheduler support.

-- When the daily "are you okay?" message goes out, in the PARENT's local time.
alter table public.care_recipients
  add column checkin_time time not null default '08:00';

-- Creates dose events for every active schedule whose local clock time falls inside the horizon.
-- Idempotent: (schedule_id, scheduled_at) is unique, so running it every 5 minutes is safe.
-- All timezone maths happens here, in the parent's timezone; scheduled_at is stored in UTC.
--
-- Rules:
--  * only days of the week, start_date and end_date of the schedule (all evaluated in the parent's local date)
--  * never create events for times before the schedule existed (adding a medicine at 10:00 must not
--    produce a 08:00 "missed" dose), and none older than the grace period
--  * pending events of medicines that were paused are removed
create function public.generate_dose_events(
  p_now timestamptz default now(),
  p_horizon interval default '24 hours',
  p_grace interval default '15 minutes'
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_created integer;
begin
  with candidates as (
    select
      s.id as schedule_id,
      ((d.day + s.time_of_day) at time zone r.timezone) as scheduled_at,
      s.created_at
    from public.medicine_schedules s
    join public.medicines m on m.id = s.medicine_id and m.active
    join public.care_recipients r on r.id = m.recipient_id
    cross join lateral generate_series(
      ((p_now at time zone r.timezone)::date - 1)::timestamp,
      ((p_now at time zone r.timezone)::date + 2)::timestamp,
      interval '1 day'
    ) as d(day)
    where d.day::date >= s.start_date
      and (s.end_date is null or d.day::date <= s.end_date)
      and extract(dow from d.day)::smallint = any (s.days_of_week)
  ), inserted as (
    insert into public.dose_events (schedule_id, scheduled_at)
    select schedule_id, scheduled_at
    from candidates
    where scheduled_at >= p_now - p_grace
      and scheduled_at <= p_now + p_horizon
      and scheduled_at >= created_at
    on conflict (schedule_id, scheduled_at) do nothing
    returning 1
  )
  select count(*) into v_created from inserted;

  delete from public.dose_events de
  using public.medicine_schedules s, public.medicines m
  where de.schedule_id = s.id and s.medicine_id = m.id
    and de.status = 'pending' and not m.active;

  return v_created;
end $$;

-- Only the backend (service role) runs the scheduler.
revoke execute on function public.generate_dose_events(timestamptz, interval, interval) from public, anon, authenticated;
grant execute on function public.generate_dose_events(timestamptz, interval, interval) to service_role;
