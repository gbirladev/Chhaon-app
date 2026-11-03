-- Scheduler heartbeat (migration 0040). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(11);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a4000000-0000-0000-0000-000000000001', 'hb-user@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
-- a clean slate inside this transaction (the real table may already hold a heartbeat from a running scheduler)
create temp table hb_saved as select * from public.scheduler_heartbeat;
delete from public.scheduler_heartbeat;

-- signed-in user
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a4000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(public.scheduler_health(), 'null'::jsonb, 'before any tick was recorded the answer is null (nothing to compare with)');
select throws_ok($$select public.touch_scheduler_heartbeat()$$, '42501', null, 'a signed-in user cannot record a heartbeat');
select throws_ok($$select * from public.scheduler_heartbeat$$, '42501', null, 'a signed-in user cannot read the table directly');
reset role;
select set_config('request.jwt.claims', '', true);

-- the server records a tick
set local role service_role;
select lives_ok($$select public.touch_scheduler_heartbeat()$$, 'the service role can record a heartbeat');
reset role;
select is((select count(*)::int from public.scheduler_heartbeat), 1, 'exactly one row');
select is((select (public.scheduler_health() ->> 'seconds_ago')::int < 5), true, 'a fresh heartbeat says it ran a moment ago');
-- recording again updates the one row
update public.scheduler_heartbeat set last_tick_at = now() - interval '3 hours';
select is((select (public.scheduler_health() ->> 'seconds_ago')::int between 10790 and 10850), true, 'a stale heartbeat reports how long ago (about 3 hours)');
select public.touch_scheduler_heartbeat();
select is((select count(*)::int from public.scheduler_heartbeat), 1, 'recording again still keeps one row');
select is((select (public.scheduler_health() ->> 'seconds_ago')::int < 5), true, 'and refreshes the time');

-- visitors
set local role anon;
select throws_ok($$select public.scheduler_health()$$, '42501', null, 'an anonymous visitor cannot ask');
select throws_ok($$select public.touch_scheduler_heartbeat()$$, '42501', null, 'an anonymous visitor cannot record a heartbeat');
reset role;

select * from finish();
rollback;
