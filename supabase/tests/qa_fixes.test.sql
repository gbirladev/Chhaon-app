-- QA round fixes (migration 0022). Run with: npx supabase test db  (the lead runs it; do not run alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(38);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a2000000-0000-0000-0000-000000000001', 'qa-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b2000000-0000-0000-0000-000000000002', 'qa-sib@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c2000000-0000-0000-0000-000000000003', 'qa-third@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

-- these tests exercise the hard limits of the biggest plan
update public.profiles set plan = 'family' where id = 'a2000000-0000-0000-0000-000000000001';

create temp table t_tok (k text primary key, v text);
grant all on t_tok to authenticated;

-- a circle with no parent (invites must be refused)
insert into public.care_circles (id, created_by) values ('c2000000-0000-0000-0000-0000000000b1', 'a2000000-0000-0000-0000-000000000001');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a2000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.create_family_invite('c2000000-0000-0000-0000-0000000000b1')$$, 'P0001', null, 'no invite for a circle without a parent');

insert into public.care_circles (id, created_by) values ('c2000000-0000-0000-0000-0000000000a1', 'a2000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation)
  values ('d2000000-0000-0000-0000-0000000000d1', 'c2000000-0000-0000-0000-0000000000a1', E'Ra\nj\tesh  Sharma', E'Pa\npa', 'father');
select is((select name from public.care_recipients where id = 'd2000000-0000-0000-0000-0000000000d1'), 'Ra j esh Sharma', 'control characters in a parent name become spaces');
select is((select call_name from public.care_recipients where id = 'd2000000-0000-0000-0000-0000000000d1'), 'Pa pa', 'and in the call name');
select throws_ok($$update public.care_recipients set conditions = (select array_agg('c' || i) from generate_series(1, 31) i) where id = 'd2000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'at most 30 conditions');
select throws_ok($$update public.care_recipients set allergies = array[repeat('x', 81)] where id = 'd2000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'allergy text at most 80 characters');

-- medicines
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', '   ', null, null, null, 10, 2, array['08:00']::time[])$$, '22023', null, 'blank medicine name refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', E'Met\nformin', null, null, null, 10, 2, array['08:00']::time[])$$, '22023', null, 'newline in a medicine name refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', repeat('a', 101), null, null, null, 10, 2, array['08:00']::time[])$$, '22023', null, 'name longer than 100 refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'Many', null, null, null, 10, 2, (select array_agg((i || ':00')::time) from generate_series(0, 12) i))$$, 'P0001', null, 'more than 12 reminder times refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'Photo', null, null, 'other-parent/x.jpg', 10, 2, array['08:00']::time[])$$, '22023', null, 'photo outside the parent folder refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'Photo', null, null, 'd2000000-0000-0000-0000-0000000000d1/../x.jpg', 10, 2, array['08:00']::time[])$$, '22023', null, 'photo path with .. refused');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'Photo', null, null, 'd2000000-0000-0000-0000-0000000000d1//x.jpg', 10, 2, array['08:00']::time[])$$, '22023', null, 'photo path with // refused');
insert into t_tok select 'med', public.create_medicine('d2000000-0000-0000-0000-0000000000d1', '  Metformin ', '500 mg', null, 'd2000000-0000-0000-0000-0000000000d1/a.jpg', 10, 2, array['08:00', '20:00']::time[])::text;
select is((select name from public.medicines where id = (select v::uuid from t_tok where k = 'med')), 'Metformin', 'name is trimmed');
select throws_ok($$select public.update_medicine((select v::uuid from t_tok where k = 'med'), '', '500 mg', null, null, 2, array['08:00']::time[])$$, '22023', null, 'update refuses a blank name');
select throws_ok($$select public.update_medicine((select v::uuid from t_tok where k = 'med'), 'Metformin', '500 mg', null, 'x/y.jpg', 2, array['08:00']::time[])$$, '22023', null, 'update refuses a foreign photo path');
select lives_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'M' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(2, 20) i$$, 'up to 20 medicines (the Family plan)');
select throws_ok($$select public.create_medicine('d2000000-0000-0000-0000-0000000000d1', 'One too many', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the 21st medicine is refused');

-- adjust_stock
select throws_ok($$select public.adjust_stock((select v::uuid from t_tok where k = 'med'), null)$$, '22004', null, 'adjust_stock refuses NULL');
select is((select public.adjust_stock((select v::uuid from t_tok where k = 'med'), -3)), 7, 'adjust_stock keeps delta semantics');
select throws_ok($$select public.adjust_stock((select v::uuid from t_tok where k = 'med'), 2147483647)$$, '22003', null, 'adjust_stock overflow is a clean error');

-- appointments: bookkeeping columns are not writable by caregivers
select throws_ok($$insert into public.appointments (recipient_id, doctor_name, datetime, reminded_morning_at) values ('d2000000-0000-0000-0000-0000000000d1', 'Dr A', now() + interval '3 days', now())$$, '42501', null, 'caregiver cannot insert reminded_* columns');
select throws_ok($$insert into public.appointments (recipient_id, doctor_name, datetime, created_at) values ('d2000000-0000-0000-0000-0000000000d1', 'Dr A', now() + interval '3 days', now())$$, '42501', null, 'caregiver cannot insert created_at');
select lives_ok($$insert into public.appointments (recipient_id, doctor_name, datetime, notes, location, remind) values ('d2000000-0000-0000-0000-0000000000d1', 'Dr A', now() + interval '3 days', 'n', 'l', true)$$, 'normal appointment insert still works');
select is((select created_by from public.appointments where doctor_name = 'Dr A'), 'a2000000-0000-0000-0000-000000000001'::uuid, 'created_by is still stamped by the trigger');

-- reports: only date and type are editable
insert into public.reports (recipient_id, uploaded_by, file_url) values ('d2000000-0000-0000-0000-0000000000d1', 'a2000000-0000-0000-0000-000000000001', 'd2000000-0000-0000-0000-0000000000d1/r.pdf');
select throws_ok($$update public.reports set ai_summary = 'forged' where recipient_id = 'd2000000-0000-0000-0000-0000000000d1'$$, '42501', null, 'caregiver cannot write ai_summary');
select throws_ok($$update public.reports set file_url = 'd2000000-0000-0000-0000-0000000000d1/other.pdf' where recipient_id = 'd2000000-0000-0000-0000-0000000000d1'$$, '42501', null, 'caregiver cannot change file_url');
select lives_ok($$update public.reports set report_date = current_date, type = 'blood' where recipient_id = 'd2000000-0000-0000-0000-0000000000d1'$$, 'report date and type are editable');

-- profiles
select throws_ok($$update public.profiles set phone = 'call me' where id = 'a2000000-0000-0000-0000-000000000001'$$, '23514', null, 'bad phone refused');
select throws_ok($$update public.profiles set email = 'not-an-email' where id = 'a2000000-0000-0000-0000-000000000001'$$, '23514', null, 'bad email refused');
select throws_ok($$update public.profiles set timezone = 'Mars/Base' where id = 'a2000000-0000-0000-0000-000000000001'$$, '22023', null, 'bad time zone refused');
select throws_ok($$update public.profiles set policy_version = repeat('v', 41) where id = 'a2000000-0000-0000-0000-000000000001'$$, '23514', null, 'long policy_version refused');
select lives_ok($$update public.profiles set phone = '+447700900123', timezone = 'Europe/London' where id = 'a2000000-0000-0000-0000-000000000001'$$, 'good phone and zone accepted');

-- invites: inviter must still be a member
select set_config('request.jwt.claims', '{"sub":"a2000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into t_tok select 'one', public.create_family_invite('c2000000-0000-0000-0000-0000000000a1');
select set_config('request.jwt.claims', '{"sub":"b2000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$select public.accept_family_invite((select v from t_tok where k = 'one'))$$, 'sibling joins');
insert into t_tok select 'two', public.create_family_invite('c2000000-0000-0000-0000-0000000000a1');
select lives_ok($$select public.leave_circle('c2000000-0000-0000-0000-0000000000a1')$$, 'sibling leaves');
select set_config('request.jwt.claims', '{"sub":"c2000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select throws_ok($$select public.accept_family_invite((select v from t_tok where k = 'two'))$$, 'P0001', null, 'a leaver''s pending invite is dead');
select is((select public.preview_family_invite(E'a\\u0000b' ) ->> 'status'), 'invalid', 'odd token is simply invalid');

-- time zone change removes pending doses (even ones whose time is already past by the database clock)
reset role;
insert into public.dose_events (schedule_id, scheduled_at)
  select id, now() - interval '5 minutes' from public.medicine_schedules where medicine_id = (select v::uuid from t_tok where k = 'med') limit 1;
select is((select count(*)::int from public.dose_events where status = 'pending' and schedule_id in (select id from public.medicine_schedules where medicine_id = (select v::uuid from t_tok where k = 'med'))), 1, 'a pending dose exists');
update public.care_recipients set timezone = 'America/Los_Angeles' where id = 'd2000000-0000-0000-0000-0000000000d1';
select is((select count(*)::int from public.dose_events where schedule_id in (select id from public.medicine_schedules where medicine_id = (select v::uuid from t_tok where k = 'med'))), 0, 'changing the time zone clears pending doses');

select * from finish();
rollback;
