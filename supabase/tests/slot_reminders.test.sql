-- One reminder per time slot (migration 0030). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a3000000-0000-0000-0000-000000000001', 'slot-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set plan = 'ultra' where id = 'a3000000-0000-0000-0000-000000000001';
insert into public.care_circles (id, created_by) values ('c3000000-0000-0000-0000-0000000000a1', 'a3000000-0000-0000-0000-000000000001'), ('c3000000-0000-0000-0000-0000000000a2', 'a3000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, relation) values
  ('d3000000-0000-0000-0000-0000000000a1', 'c3000000-0000-0000-0000-0000000000a1', 'Parent one', 'father'),
  ('d3000000-0000-0000-0000-0000000000a2', 'c3000000-0000-0000-0000-0000000000a2', 'Parent two', 'mother');
insert into public.medicines (id, recipient_id, name, dosage) values
  ('e3000000-0000-0000-0000-0000000000a1', 'd3000000-0000-0000-0000-0000000000a1', 'Med A', '500 mg'),
  ('e3000000-0000-0000-0000-0000000000a2', 'd3000000-0000-0000-0000-0000000000a1', 'Med B', '1 tablet'),
  ('e3000000-0000-0000-0000-0000000000a3', 'd3000000-0000-0000-0000-0000000000a2', 'Med C', '1 tablet');
insert into public.medicine_schedules (id, medicine_id, time_of_day) values
  ('f3000000-0000-0000-0000-0000000000a1', 'e3000000-0000-0000-0000-0000000000a1', '08:00'),
  ('f3000000-0000-0000-0000-0000000000a2', 'e3000000-0000-0000-0000-0000000000a2', '08:00'),
  ('f3000000-0000-0000-0000-0000000000a3', 'e3000000-0000-0000-0000-0000000000a3', '08:00');

-- three doses due at the same moment: two for parent one (one message covers both), one for parent two
insert into public.dose_events (id, schedule_id, scheduled_at, status, sent_at, whatsapp_message_id) values
  ('a4000000-0000-0000-0000-0000000000a1', 'f3000000-0000-0000-0000-0000000000a1', '2026-10-10 02:30+00', 'sent', now(), 'wamid.SHARED'),
  ('a4000000-0000-0000-0000-0000000000a2', 'f3000000-0000-0000-0000-0000000000a2', '2026-10-10 02:30+00', 'sent', now(), 'wamid.SHARED'),
  ('a4000000-0000-0000-0000-0000000000a3', 'f3000000-0000-0000-0000-0000000000a3', '2026-10-10 02:30+00', 'sent', now(), 'wamid.OTHER');
select is((select count(*)::int from public.dose_events where whatsapp_message_id = 'wamid.SHARED'), 2, 'one WhatsApp message id can cover several doses');

-- ordinary users cannot confirm doses this way; only the backend can
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a3000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select * from public.confirm_dose_slot('a4000000-0000-0000-0000-0000000000a1')$$, '42501', null, 'a logged-in user cannot call confirm_dose_slot');
reset role;
select set_config('request.jwt.claims', '', true);

-- the backend confirms the whole slot of THAT parent and nothing else
set local role service_role;
select is((select count(*)::int from public.confirm_dose_slot('a4000000-0000-0000-0000-0000000000a1')), 2, 'both doses of the slot are confirmed');
reset role;
select is((select status::text from public.dose_events where id = 'a4000000-0000-0000-0000-0000000000a2'), 'taken', 'the other dose of the same slot is taken too');
select is((select status::text from public.dose_events where id = 'a4000000-0000-0000-0000-0000000000a3'), 'sent', 'another parent''s dose at the same moment is untouched');
set local role service_role;
select is((select count(*)::int from public.confirm_dose_slot('a4000000-0000-0000-0000-0000000000a1')), 0, 'a second tap confirms nothing new');
reset role;

-- doses still waiting (pending) or skipped are not turned into "taken"
update public.dose_events set status = 'pending' where id = 'a4000000-0000-0000-0000-0000000000a3';
set local role service_role;
select is((select count(*)::int from public.confirm_dose_slot('a4000000-0000-0000-0000-0000000000a3')), 0, 'a dose that was never sent cannot be confirmed');
reset role;

select * from finish();
rollback;
