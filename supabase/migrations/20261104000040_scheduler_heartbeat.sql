-- Scheduler heartbeat: every scheduler tick records when it last ran, so the app can tell the family when reminders have stopped.
--
-- WHY: after the move to AWS the reminder job silently stopped (2026-11-02 21:00 IST) and nothing said so: Today kept showing a morning dose as
-- "next" at midnight and tomorrow's doses were never created. A tick that simply does not run cannot report itself, so the app watches the clock.
--
-- HOW: the scheduler calls touch_scheduler_heartbeat() at the end of each tick (service role / database owner only). The app asks
-- scheduler_health() (any signed-in user) and gets NULL until the first tick was ever recorded (nothing to compare with, so no false alarm),
-- then {last_tick_at, seconds_ago}. The table itself is closed to everyone: only these two functions touch it.

create table public.scheduler_heartbeat (
  id           integer primary key default 1 check (id = 1),   -- one row
  last_tick_at timestamptz not null
);
alter table public.scheduler_heartbeat enable row level security;   -- no policy: no direct reads or writes for anyone but the owner
revoke all on table public.scheduler_heartbeat from public, anon, authenticated;
grant select, insert, update on table public.scheduler_heartbeat to service_role;

create or replace function public.touch_scheduler_heartbeat() returns void
language sql security definer set search_path = '' as $$
  insert into public.scheduler_heartbeat (id, last_tick_at) values (1, now())
  on conflict (id) do update set last_tick_at = excluded.last_tick_at;
$$;
revoke all on function public.touch_scheduler_heartbeat() from public, anon, authenticated;
grant execute on function public.touch_scheduler_heartbeat() to service_role;

create or replace function public.scheduler_health() returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(
    (select jsonb_build_object('last_tick_at', h.last_tick_at, 'seconds_ago', floor(extract(epoch from now() - h.last_tick_at))::integer)
       from public.scheduler_heartbeat h where h.id = 1),
    'null'::jsonb);
$$;
revoke all on function public.scheduler_health() from public, anon;
grant execute on function public.scheduler_health() to authenticated, service_role;
