-- Canary family (migration 20261106000055, docs/canary.md). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

-- an admin (to read the stats) and one ordinary family, so the stats have something to compare
insert into auth.users (id, email, instance_id, aud, role) values
  ('a9000000-0000-0000-0000-000000000001', 'canary-admin@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.profiles (id) values ('a9000000-0000-0000-0000-000000000001') on conflict (id) do nothing;
update public.profiles set is_admin = true where id = 'a9000000-0000-0000-0000-000000000001';

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
create temp table before_stats as select public.admin_stats() s;
reset role;
select set_config('request.jwt.claims', '', true);   -- back to the SQL editor's view (no signed-in user)

-- setup: refuses the placeholder, then makes one family, twice without duplicates
select throws_like($$select public.canary_setup('+9199999xxxxx')$$, '%E.164%', 'the unedited placeholder is refused');
select is(public.canary_setup('+919999900071'), 'cacacaca-0000-4000-8000-000000000003'::uuid, 'setup returns the canary person');
select is(public.canary_setup('+919999900071'), 'cacacaca-0000-4000-8000-000000000003'::uuid, 'setup can run again');
select is((select count(*)::int from public.care_recipients where is_canary), 1, 'one canary person');
select is((select count(*)::int from public.profiles where is_canary), 1, 'one canary caregiver');
select is((select count(*)::int from public.medicine_schedules s join public.medicines m on m.id = s.medicine_id
            join public.care_recipients r on r.id = m.recipient_id where r.is_canary), 6, 'six reminder times');
select ok((select consent_at is not null and not checkin_enabled and reminder_to = 'person' and timezone = 'Asia/Kolkata'
             from public.care_recipients where is_canary), 'consent given, no check-in, reminder to the person, India time');
select ok((select stock_count is null and refill_threshold is null and photo_url is null from public.medicines
            where id = 'cacacaca-0000-4000-8000-000000000004'), 'no stock tracking, no photo');

-- nobody hears about the canary
insert into public.notifications (user_id, type, payload)
  values ('cacacaca-0000-4000-8000-000000000001', 'refill_needed', '{}'::jsonb);
insert into public.notifications (user_id, type, payload)
  values ('a9000000-0000-0000-0000-000000000001', 'dose_missed', '{"recipient_id":"cacacaca-0000-4000-8000-000000000003"}'::jsonb);
select is((select count(*)::int from public.notifications where user_id = 'cacacaca-0000-4000-8000-000000000001'
             or payload ->> 'recipient_id' = 'cacacaca-0000-4000-8000-000000000003'), 0, 'no notification for or about the canary is stored');
insert into public.notifications (user_id, type, payload) values ('a9000000-0000-0000-0000-000000000001', 'dose_missed', '{}'::jsonb);
select is((select count(*)::int from public.notifications where user_id = 'a9000000-0000-0000-0000-000000000001'), 1,
  'other notifications are stored as before');

-- a sent and answered canary reminder does not change the pilot numbers
insert into public.dose_events (schedule_id, scheduled_at, status, sent_at, responded_at)
  select s.id, now() - interval '1 hour', 'taken', now() - interval '1 hour', now() - interval '59 minutes'
    from public.medicine_schedules s where s.medicine_id = 'cacacaca-0000-4000-8000-000000000004' limit 1;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(public.admin_stats() -> 'parents', (select s -> 'parents' from before_stats), 'parents leave the canary out');
select is(public.admin_stats() -> 'reminders_7d', (select s -> 'reminders_7d' from before_stats), 'reminders leave the canary out');
select is(public.admin_stats() -> 'caregivers' -> 'total', (select s -> 'caregivers' -> 'total' from before_stats), 'caregivers leave the canary out');
select throws_ok($$select public.canary_setup('+919999900072')$$, '42501', null, 'a signed-in user cannot run the setup');
reset role;
select set_config('request.jwt.claims', '', true);   -- back to the SQL editor's view (no signed-in user)

-- remove: everything goes, twice is fine
select is(public.canary_remove(), 1, 'remove deletes the canary');
select ok((select count(*) from public.care_recipients where is_canary) = 0 and (select count(*) from public.profiles where is_canary) = 0
          and public.canary_remove() = 0, 'nothing left, and removing again does nothing');

select * from finish();
rollback;
