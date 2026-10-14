-- Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(56);

-- Two unrelated caregivers; the signup trigger creates their profiles.
insert into auth.users (id, email, instance_id, aud, role) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'alice@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'bob@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

select is((select count(*)::int from public.profiles where id in ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002')), 2, 'signup trigger creates profiles');

update public.profiles set plan = 'family' where id = 'aaaaaaaa-0000-0000-0000-000000000001';  -- Alice makes several parents below

-- ---- Alice builds a circle with a parent, a medicine, a schedule
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);

insert into public.care_circles (id, created_by) values ('cccccccc-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001');
select is((select role::text from public.care_circle_members where circle_id = 'cccccccc-0000-0000-0000-000000000001'),
  'owner', 'circle creator becomes owner');

insert into public.care_recipients (id, circle_id, name, whatsapp_phone, consent_at)
values ('dddddddd-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', 'Papa', '+919876543210', now());
select is((select consent_at from public.care_recipients where id = 'dddddddd-0000-0000-0000-000000000001'),
  null, 'caregiver cannot set consent_at on insert');

select throws_ok(
  $$update public.care_recipients set consent_at = now() where id = 'dddddddd-0000-0000-0000-000000000001'$$,
  '42501', null, 'caregiver cannot set consent_at on update');

insert into public.medicines (id, recipient_id, name) values ('eeeeeeee-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000001', 'Metformin');
insert into public.medicine_schedules (medicine_id, time_of_day) values ('eeeeeeee-0000-0000-0000-000000000001', '08:00');
select is((select count(*)::int from public.medicines), 1, 'owner sees own medicine');

-- ---- Bob (not in the circle) sees and changes nothing
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-0000-0000-000000000002","role":"authenticated"}', true);

select is((select count(*)::int from public.care_circles), 0, 'outsider cannot see circle');
select is((select count(*)::int from public.care_recipients), 0, 'outsider cannot see parent');
select is((select count(*)::int from public.medicines), 0, 'outsider cannot see medicines');
select is((select count(*)::int from public.medicine_schedules), 0, 'outsider cannot see schedules');
select is((select count(*)::int from public.profiles where id = 'aaaaaaaa-0000-0000-0000-000000000001'), 0, 'outsider cannot see other profiles');

select throws_ok(
  $$insert into public.medicines (recipient_id, name) values ('dddddddd-0000-0000-0000-000000000001', 'Injected')$$,
  '42501', null, 'outsider cannot insert into someone else''s parent');

select throws_ok(
  $$insert into public.care_circle_members (circle_id, user_id) values ('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002')$$,
  '42501', null, 'outsider cannot add themselves to a circle');

-- ---- caregivers never write dose events directly
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok(
  $$insert into public.dose_events (schedule_id, scheduled_at, status)
    select id, now(), 'taken' from public.medicine_schedules limit 1$$,
  '42501', null, 'caregiver cannot forge dose events');

select is((select count(*)::int from public.whatsapp_events), 0, 'whatsapp_events is invisible to clients');

-- ---- step 2 RPCs
create temp table t_ids (k text primary key, v uuid);
grant all on t_ids to authenticated;

insert into t_ids select 'parent', public.create_care_recipient(
  '{"name":"Mummy","relation":"mother","whatsapp_phone":"+919811122233","conditions":["Diabetes","High BP"],
    "emergency_contacts":[{"name":"Alice","phone":"+14155550123"}],"caregiver_attested":true}'::jsonb);

select is((select array_length(conditions, 1) from public.care_recipients where id = (select v from t_ids where k = 'parent')),
  2, 'create_care_recipient stores conditions array');
select ok((select caregiver_attested_at is not null and consent_at is null from public.care_recipients where id = (select v from t_ids where k = 'parent')),
  'attestation recorded, parent consent still empty');

insert into t_ids select 'med', public.create_medicine(
  (select v from t_ids where k = 'parent'), 'Metformin', '500mg', 'after_food', null, 30, 5, array['08:00','20:00']::time[]);
select is((select count(*)::int from public.medicine_schedules where medicine_id = (select v from t_ids where k = 'med')),
  2, 'create_medicine creates one schedule per time');

select throws_ok(
  $$select public.create_medicine((select v from t_ids where k = 'parent'), 'X', null, null, null, null, null, array[]::time[])$$,
  '22023', null, 'create_medicine requires a reminder time');

select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-0000-0000-000000000002","role":"authenticated"}', true);
select throws_ok(
  $$select public.create_medicine((select v from t_ids where k = 'parent'), 'Sneaky', null, null, null, null, null, array['09:00']::time[])$$,
  '42501', null, 'outsider cannot create medicine via RPC');
select is((select count(*)::int from public.care_recipients where id = (select v from t_ids where k = 'parent')),
  0, 'outsider cannot see RPC-created parent');

-- ---- step 3: consent belongs to a phone number
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);   -- act as the backend
update public.care_recipients set consent_at = now(), consent_requested_at = now()
  where id = (select v from t_ids where k = 'parent');
select isnt((select consent_at from public.care_recipients where id = (select v from t_ids where k = 'parent')), null,
  'system can record the parent''s consent');
update public.care_recipients set whatsapp_phone = '+919000000001' where id = (select v from t_ids where k = 'parent');
select is((select consent_at from public.care_recipients where id = (select v from t_ids where k = 'parent')), null,
  'changing the WhatsApp number resets consent');
update public.care_recipients set name = 'Mummy G' where id = (select v from t_ids where k = 'parent');
select is((select whatsapp_phone from public.care_recipients where id = (select v from t_ids where k = 'parent')), '+919000000001',
  'other edits leave the number alone');

-- ---- step 4: dose generation (acts as the backend; Mummy lives in Asia/Kolkata = UTC+5:30)
-- Assertions count only THIS test's medicine, so other data in the database cannot affect them.
update public.care_recipients set timezone = 'Asia/Kolkata' where id = (select v from t_ids where k = 'parent');
update public.medicine_schedules set created_at = '2026-10-01 00:00:00+00', start_date = '2026-01-01' where medicine_id = (select v from t_ids where k = 'med');   -- create_medicine starts 'today'; these tests use a fixed October clock
-- the 'med' fixture has two schedules: 08:00 and 20:00 IST, every day

select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 1,
  'at 07:30 IST a 1-hour horizon creates only the 08:00 dose');
select is((select scheduled_at from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med') and s.time_of_day = '08:00'),
  '2026-10-10 02:30:00+00'::timestamptz, '08:00 IST is stored as 02:30 UTC');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 1, 'running again creates nothing (idempotent)');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '25 hours');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 3,
  'a 25-hour horizon adds the 20:00 dose today and the 08:00 dose tomorrow (3 in total for this medicine)');

-- weekday filter: 2026-10-10 is a Saturday (dow 6); only allow Monday
update public.medicine_schedules set days_of_week = '{1}' where medicine_id = (select v from t_ids where k = 'med');
delete from public.dose_events de using public.medicine_schedules s where s.id = de.schedule_id and s.medicine_id = (select v from t_ids where k = 'med');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '24 hours');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 0, 'days_of_week is respected');
update public.medicine_schedules set days_of_week = '{0,1,2,3,4,5,6}', start_date = '2026-10-11' where medicine_id = (select v from t_ids where k = 'med');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 0, 'nothing before start_date');
update public.medicine_schedules set start_date = '2026-01-01', created_at = '2026-10-10 02:40:00+00' where medicine_id = (select v from t_ids where k = 'med');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 0,
  'a schedule created at 08:10 IST does not produce a 08:00 dose that is already past');

-- pausing a medicine removes its pending doses
update public.medicine_schedules set created_at = now() - interval '3 days' where medicine_id = (select v from t_ids where k = 'med');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
update public.medicines set active = false where id = (select v from t_ids where k = 'med');
select public.generate_dose_events('2026-10-10 02:00:00+00', interval '1 hour');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
           where s.medicine_id = (select v from t_ids where k = 'med')), 0, 'pausing a medicine removes its pending doses');

-- ---- low-stock alerts (acts as the backend). Alice is the only member of Mummy's circle.
update public.medicines set active = true where id = (select v from t_ids where k = 'med');
delete from public.notifications where user_id = 'aaaaaaaa-0000-0000-0000-000000000001';
create temp table t_low (id uuid);
grant all on t_low to authenticated;
with m as (insert into public.medicines (recipient_id, name, stock_count, refill_threshold)
           values ((select v from t_ids where k = 'parent'), 'Amlodipine', 12, 5) returning id)
insert into t_low select id from m;

select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 0, 'a well-stocked medicine raises no alert');

select public.consume_stock((select id from t_low), 6);
select is((select stock_count from public.medicines where id = (select id from t_low)), 6, 'consume_stock uses up stock');
select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 0, 'still above the refill level: no alert');

select public.consume_stock((select id from t_low), 1);
select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 1, 'reaching the refill level alerts the family once');

select public.consume_stock((select id from t_low), 1);
select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 1, 'staying low does not alert again');

update public.medicines set stock_count = 30 where id = (select id from t_low);
update public.medicines set stock_count = 4 where id = (select id from t_low);
select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 2, 'after a refill, running low again alerts again');

with m as (insert into public.medicines (recipient_id, name, stock_count, refill_threshold)
           values ((select v from t_ids where k = 'parent'), 'Added already low', 3, 5) returning id)
insert into t_low select id from m;
select is((select count(*)::int from public.notifications where type = 'refill_needed' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 3, 'a medicine added while already low alerts immediately');

-- ---- out of stock: a second alert when the medicine runs out
select public.consume_stock((select id from t_low limit 1), 4);   -- 'Amlodipine' was at 4 after the refill/drop tests above
select is((select count(*)::int from public.notifications where type = 'out_of_stock' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 1, 'running out raises its own alert');
select public.consume_stock((select id from t_low limit 1), 1);
select is((select count(*)::int from public.notifications where type = 'out_of_stock' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 1, 'staying at zero does not repeat it');
update public.medicines set stock_count = 40 where id = (select id from t_low limit 1);
update public.medicines set stock_count = 0 where id = (select id from t_low limit 1);
select is((select count(*)::int from public.notifications where type = 'out_of_stock' and user_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 2, 'after a refill, running out again alerts again');

-- ---- hardening (QA round)
select throws_ok($$update public.care_recipients set timezone = 'Mars/Olympus' where id = (select v from t_ids where k = 'parent')$$,
  '22023', null, 'an unknown time zone is rejected');

update public.care_recipients set consent_requested_at = now() where id = (select v from t_ids where k = 'parent');   -- the system stamped a request
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$update public.care_recipients set consent_requested_at = null where id = (select v from t_ids where k = 'parent')$$,
  '42501', null, 'a caregiver cannot reset the consent-request rate limit');
select throws_ok($$update public.care_circle_members set user_id = 'bbbbbbbb-0000-0000-0000-000000000002' where user_id = 'aaaaaaaa-0000-0000-0000-000000000001'$$,
  '42501', null, 'a membership cannot be re-pointed at another user');
select throws_ok($$update public.medicines set photo_url = 'cccccccc-0000-0000-0000-000000000009/stolen.jpg' where id = (select v from t_ids where k = 'med')$$,
  '23514', null, 'a photo path must be inside the medicine''s own parent folder');
select throws_ok($$insert into public.medicine_schedules (medicine_id, time_of_day) values ((select v from t_ids where k = 'med'), '08:00')$$,
  '23505', null, 'one schedule per medicine per clock time');

-- ---- round-2: a caregiver cannot rewrite who uploaded a report
insert into public.reports (recipient_id, file_url) values ((select v from t_ids where k = 'parent'), 'x/report.pdf');
select is((select uploaded_by from public.reports order by created_at desc limit 1), 'aaaaaaaa-0000-0000-0000-000000000001'::uuid, 'the uploader is stamped on insert');
select throws_ok($$update public.reports set uploaded_by = 'bbbbbbbb-0000-0000-0000-000000000002' where file_url = 'x/report.pdf'$$, '42501', null, 'caregivers may only edit a report''s date and type (column grants)');
select is((select uploaded_by from public.reports where file_url = 'x/report.pdf'), 'aaaaaaaa-0000-0000-0000-000000000001'::uuid, 'the uploader cannot be rewritten afterwards');


-- ---- relations and the name the caregiver uses
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select lives_ok($$update public.care_recipients set relation = 'grandmother', call_name = 'Dadi' where id = (select v from t_ids where k = 'parent')$$, 'more relations are accepted, with a call name');
select throws_ok($$update public.care_recipients set relation = 'cousin' where id = (select v from t_ids where k = 'parent')$$, '23514', null, 'an unknown relation is rejected');
select throws_ok($$update public.care_recipients set call_name = repeat('x', 41) where id = (select v from t_ids where k = 'parent')$$, '23514', null, 'the call name has a length limit');

-- ---- caregiver alerts: only the system may set the alert number/consent; the caregiver may turn alerts off
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.profiles set alert_phone = '+919000011112', alert_consent_at = now(), alert_confirmed_at = now() where id = 'aaaaaaaa-0000-0000-0000-000000000001';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$update public.profiles set alert_phone = '+919000099999' where id = 'aaaaaaaa-0000-0000-0000-000000000001'$$, '42501', null, 'a caregiver cannot choose where alerts are sent by editing the number');
select throws_ok($$update public.profiles set alert_confirmed_at = null where id = 'aaaaaaaa-0000-0000-0000-000000000001'$$, '42501', null, 'a caregiver cannot rewrite the confirmation');
select lives_ok($$update public.profiles set alert_consent_at = null where id = 'aaaaaaaa-0000-0000-0000-000000000001'$$, 'a caregiver can always turn alerts off');
select throws_ok($$update public.profiles set alert_consent_at = now() where id = 'aaaaaaaa-0000-0000-0000-000000000001'$$, '42501', null, 'but cannot switch them on without the number confirming');

select * from finish();
rollback;
