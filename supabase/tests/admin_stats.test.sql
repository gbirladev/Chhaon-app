-- Pilot stats (migration 0028). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a8000000-0000-0000-0000-000000000001', 'stats-admin@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b8000000-0000-0000-0000-000000000002', 'stats-normal@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set is_admin = true where id = 'a8000000-0000-0000-0000-000000000001';   -- done by hand, as the owner of the app would

-- a parent with a phone number, so the "no personal data" check below means something
insert into public.care_circles (id, created_by) values ('c8000000-0000-0000-0000-0000000000c1', 'b8000000-0000-0000-0000-000000000002');
insert into public.care_recipients (circle_id, name, relation, whatsapp_phone) values ('c8000000-0000-0000-0000-0000000000c1', 'Secret Name', 'father', '+919876543210');

-- an ordinary caregiver
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b8000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select throws_ok($$select public.admin_stats()$$, '42501', null, 'a normal caregiver cannot read the stats');
select throws_ok($$update public.profiles set is_admin = true where id = 'b8000000-0000-0000-0000-000000000002'$$, '42501', null, 'nobody can make themselves an admin');
select is((select is_admin from public.profiles where id = 'b8000000-0000-0000-0000-000000000002'), false, 'still not an admin');

-- the admin
select set_config('request.jwt.claims', '{"sub":"a8000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select ok(public.admin_stats() ?& array['caregivers', 'parents', 'medicines', 'reminders_7d', 'checkins_7d', 'whatsapp_7d', 'plans', 'family', 'daily'], 'the admin gets every section');
select cmp_ok((public.admin_stats() -> 'caregivers' ->> 'total')::int, '>=', 2, 'it counts the caregivers');
select cmp_ok((public.admin_stats() -> 'parents' ->> 'total')::int, '>=', 1, 'it counts the parents');
select ok(public.admin_stats()::text !~ '@|\+[0-9]{8}|Secret Name', 'no email, phone number or name appears in the stats');
select is(jsonb_array_length(public.admin_stats(1000) -> 'daily'), 90, 'the daily series is capped at 90 days');

select * from finish();
rollback;
