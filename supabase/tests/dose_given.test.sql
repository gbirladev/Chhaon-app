-- "Mark as given" (migration 20261105000047). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a7000000-0000-0000-0000-000000000001', 'given-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a7000000-0000-0000-0000-000000000002', 'given-sister@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a7000000-0000-0000-0000-000000000003', 'given-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set plan = 'ultra' where id in ('a7000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000002');
insert into public.care_circles (id, created_by) values ('c7000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001');
insert into public.care_circle_members (circle_id, user_id, role) values
  ('c7000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000002', 'member');
insert into public.care_recipients (id, circle_id, name, relation) values
  ('d7000000-0000-0000-0000-0000000000a1', 'c7000000-0000-0000-0000-0000000000a1', 'Parent', 'father');
insert into public.medicines (id, recipient_id, name, dosage, stock_count) values
  ('e7000000-0000-0000-0000-0000000000a1', 'd7000000-0000-0000-0000-0000000000a1', 'Med A', '2 tablets', 20),
  ('e7000000-0000-0000-0000-0000000000a2', 'd7000000-0000-0000-0000-0000000000a1', 'Med B', '1 tablet', 0);
insert into public.medicine_schedules (id, medicine_id, time_of_day) values
  ('f7000000-0000-0000-0000-0000000000a1', 'e7000000-0000-0000-0000-0000000000a1', '08:00'),
  ('f7000000-0000-0000-0000-0000000000a2', 'e7000000-0000-0000-0000-0000000000a2', '08:00');
insert into public.dose_events (id, schedule_id, scheduled_at, status, sent_at) values
  ('a8000000-0000-0000-0000-0000000000a1', 'f7000000-0000-0000-0000-0000000000a1', now() - interval '20 minutes', 'sent', now() - interval '19 minutes'),
  ('a8000000-0000-0000-0000-0000000000a2', 'f7000000-0000-0000-0000-0000000000a2', now() - interval '20 minutes', 'missed', now() - interval '19 minutes'),
  ('a8000000-0000-0000-0000-0000000000a3', 'f7000000-0000-0000-0000-0000000000a1', now() + interval '3 hours', 'pending', null),
  ('a8000000-0000-0000-0000-0000000000a4', 'f7000000-0000-0000-0000-0000000000a1', now() - interval '2 hours', 'sent', now() - interval '2 hours');
insert into public.notifications (user_id, type, payload) values
  ('a7000000-0000-0000-0000-000000000001', 'dose_missed', jsonb_build_object('recipient_id', 'd7000000-0000-0000-0000-0000000000a1',
     'dose_event_id', 'a8000000-0000-0000-0000-0000000000a2'));

-- logged-in users cannot call the functions directly; only the backend (which checked the caller) can
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a7000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.mark_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001', 'caregiver_in_person', 1)$$,
  '42501', null, 'a logged-in user cannot call mark_dose_given');
select throws_ok($$select public.undo_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001', 10)$$,
  '42501', null, 'a logged-in user cannot call undo_dose_given');
reset role;
select set_config('request.jwt.claims', '', true);

set local role service_role;
select throws_ok($$select public.mark_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000003', 'caregiver_in_person', 1)$$,
  '42501', 'not found', 'someone outside the circle gets not found');
select throws_ok($$select public.mark_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001', 'whatsapp', 1)$$,
  '22023', null, 'only the two manual sources are accepted');
select is(public.mark_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000002', 'caregiver_told', 2)->>'result',
  'marked', 'a circle member marks a waiting dose as given');
reset role;
select is((select status::text || '/' || confirm_source || '/' || confirm_prev_status::text || '/' || confirm_stock_used
             from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a1'),
  'taken/caregiver_told/sent/2', 'status, how, the old status and the stock used are recorded');
select is((select confirmed_by from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a1'),
  'a7000000-0000-0000-0000-000000000002'::uuid, 'who marked it is recorded');
select is((select stock_count from public.medicines where id = 'e7000000-0000-0000-0000-0000000000a1'), 18, 'stock goes down like a WhatsApp reply');

set local role service_role;
select is(public.mark_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001', 'caregiver_in_person', 2)->>'result',
  'already', 'a dose already marked cannot be marked again');
select is((select count(*)::int from public.confirm_dose_slot('a8000000-0000-0000-0000-0000000000a1')), 1,
  'a later WhatsApp "all taken" only confirms the other dose still open in the slot');
reset role;
select is((select confirm_source from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a1'), 'caregiver_told',
  'the manual mark is kept');
select is((select status::text || '/' || confirm_source from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a2'),
  'taken_late/whatsapp', 'a WhatsApp confirmation is recorded as whatsapp by the trigger');

-- a missed dose: taken_late, empty stock stays 0, and the "missed" alert goes away
update public.dose_events set status = 'missed', confirm_source = null, responded_at = null where id = 'a8000000-0000-0000-0000-0000000000a2';
set local role service_role;
select is(public.mark_dose_given('a8000000-0000-0000-0000-0000000000a2', 'a7000000-0000-0000-0000-000000000001', 'caregiver_in_person', 1)->>'status',
  'taken_late', 'a missed dose becomes taken_late');
reset role;
select is((select count(*)::int from public.notifications where type = 'dose_missed'
             and payload->>'dose_event_id' = 'a8000000-0000-0000-0000-0000000000a2'), 0, 'the missed alert is removed');
select is((select confirm_stock_used from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a2'), 0,
  'nothing was taken off an empty stock');

set local role service_role;
select is(public.mark_dose_given('a8000000-0000-0000-0000-0000000000a3', 'a7000000-0000-0000-0000-000000000001', 'caregiver_in_person', 1)->>'result',
  'too_early', 'a dose hours away cannot be marked');
-- undo: only who marked it, within the time
select is(public.undo_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001', 10)->>'result',
  'not_yours', 'another member cannot undo it');
select is(public.undo_dose_given('a8000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000002', 10)->>'result',
  'undone', 'who marked it can undo it');
reset role;
select is((select status::text || '/' || coalesce(confirm_source, '-') || '/' || coalesce(confirmed_by::text, '-')
             from public.dose_events where id = 'a8000000-0000-0000-0000-0000000000a1'),
  'sent/-/-', 'undo restores the waiting status and clears who/how');
select is((select stock_count from public.medicines where id = 'e7000000-0000-0000-0000-0000000000a1'), 20, 'undo gives the stock back');

select * from finish();
rollback;
