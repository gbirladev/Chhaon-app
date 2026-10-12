-- Dose generation can be limited to specific parents (tests and one-off repairs must never touch other families).
drop function if exists public.generate_dose_events(timestamptz, interval, interval);

create function public.generate_dose_events(
  p_now timestamptz default now(),
  p_horizon interval default '24 hours',
  p_grace interval default '60 minutes',
  p_recipients uuid[] default null          -- NULL = everyone (production); otherwise only these parents
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_created integer;
begin
  with zones as (select name from pg_catalog.pg_timezone_names),
  candidates as (
    select
      s.id as schedule_id,
      ((d.day + s.time_of_day) at time zone r.timezone) as scheduled_at,
      s.created_at
    from public.medicine_schedules s
    join public.medicines m on m.id = s.medicine_id and m.active
    join public.care_recipients r on r.id = m.recipient_id
    join zones z on z.name = r.timezone
    cross join lateral generate_series(
      ((p_now at time zone r.timezone)::date - 1)::timestamp,
      ((p_now at time zone r.timezone)::date + 2)::timestamp,
      interval '1 day'
    ) as d(day)
    where (p_recipients is null or r.id = any (p_recipients))
      and d.day::date >= s.start_date
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
    and de.status = 'pending' and not m.active
    and (p_recipients is null or m.recipient_id = any (p_recipients));

  return v_created;
end $$;
revoke execute on function public.generate_dose_events(timestamptz, interval, interval, uuid[]) from public, anon, authenticated;
grant execute on function public.generate_dose_events(timestamptz, interval, interval, uuid[]) to service_role;
