-- Doses made at once after a medicine save (the backend's SchedulerService.generateNow calls generate_dose_events scoped to
-- ONE parent), and the next scheduler tick (everyone) adds nothing twice. Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a5700000-0000-0000-0000-000000000001', 'doses-now@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set plan = 'ultra' where id = 'a5700000-0000-0000-0000-000000000001';
insert into public.care_circles (id, created_by) values
  ('c5700000-0000-0000-0000-0000000000a1', 'a5700000-0000-0000-0000-000000000001'),
  ('c5700000-0000-0000-0000-0000000000a2', 'a5700000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, relation, timezone) values
  ('d5700000-0000-0000-0000-0000000000a1', 'c5700000-0000-0000-0000-0000000000a1', 'Parent one', 'father', 'Asia/Kolkata'),
  ('d5700000-0000-0000-0000-0000000000a2', 'c5700000-0000-0000-0000-0000000000a2', 'Parent two', 'mother', 'Asia/Kolkata');
insert into public.medicines (id, recipient_id, name, dosage) values
  ('e5700000-0000-0000-0000-0000000000a1', 'd5700000-0000-0000-0000-0000000000a1', 'Med A', '500 mg'),
  ('e5700000-0000-0000-0000-0000000000a2', 'd5700000-0000-0000-0000-0000000000a2', 'Med B', '1 tablet');
-- saved at 08:00 India time on 6 Nov: 07:00 has already passed today, 21:00 is still to come
insert into public.medicine_schedules (id, medicine_id, time_of_day, start_date, created_at) values
  ('f5700000-0000-0000-0000-0000000000a1', 'e5700000-0000-0000-0000-0000000000a1', '07:00', '2026-11-06', '2026-11-06 08:00+05:30'),
  ('f5700000-0000-0000-0000-0000000000a2', 'e5700000-0000-0000-0000-0000000000a1', '21:00', '2026-11-06', '2026-11-06 08:00+05:30'),
  ('f5700000-0000-0000-0000-0000000000a3', 'e5700000-0000-0000-0000-0000000000a2', '21:00', '2026-11-06', '2026-11-06 08:00+05:30');

-- right after the save: only parent one, as the backend calls it (service role)
set local role service_role;
select is(public.generate_dose_events('2026-11-06 08:00+05:30', '24 hours', '60 minutes', array['d5700000-0000-0000-0000-0000000000a1']::uuid[]), 2,
  'the save makes parent one''s upcoming doses at once (today 21:00 and tomorrow 07:00)');
reset role;
select is((select count(*)::int from public.dose_events where schedule_id = 'f5700000-0000-0000-0000-0000000000a1' and scheduled_at = '2026-11-06 07:00+05:30'), 0,
  'a time that already passed today gets no dose (nothing is sent late)');
select is((select count(*)::int from public.dose_events where schedule_id = 'f5700000-0000-0000-0000-0000000000a2' and scheduled_at = '2026-11-06 21:00+05:30' and status = 'pending'), 1,
  'today 21:00 is waiting on the Today screen');
select is((select count(*)::int from public.dose_events where schedule_id = 'f5700000-0000-0000-0000-0000000000a3'), 0,
  'another parent is not touched');

set local role service_role;
select is(public.generate_dose_events('2026-11-06 08:00+05:30', '24 hours', '60 minutes', array['d5700000-0000-0000-0000-0000000000a1']::uuid[]), 0,
  'saving again makes nothing twice');
-- the next 5-minute tick, for everyone
select lives_ok($$select public.generate_dose_events('2026-11-06 08:05+05:30', '24 hours', '60 minutes')$$, 'the next tick runs');
reset role;
select is((select count(*)::int from public.dose_events d join public.medicine_schedules s on s.id = d.schedule_id where s.medicine_id = 'e5700000-0000-0000-0000-0000000000a1'), 2,
  'the tick adds no duplicate for parent one');
select is((select count(*)::int from public.dose_events where schedule_id = 'f5700000-0000-0000-0000-0000000000a3'), 1,
  'the tick makes parent two''s dose as usual');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5700000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.generate_dose_events(now(), '24 hours', '60 minutes', array['d5700000-0000-0000-0000-0000000000a1']::uuid[])$$, '42501', null,
  'a signed-in family member cannot call it directly (server only)');
reset role;
select set_config('request.jwt.claims', '', true);

select * from finish();
rollback;
