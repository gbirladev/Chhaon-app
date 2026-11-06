-- Doctor code and "How did you hear about Chhaon?" (migration 20261106000058). Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(23);

-- an admin, a caregiver (Asha) and her brother (Ravi) in the same family, and an outsider
insert into auth.users (id, email, instance_id, aud, role) values
  ('a5800000-0000-0000-0000-000000000001', 'ref-admin@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a5800000-0000-0000-0000-000000000002', 'ref-asha@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a5800000-0000-0000-0000-000000000003', 'ref-ravi@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a5800000-0000-0000-0000-000000000004', 'ref-outside@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.profiles (id, name) values
  ('a5800000-0000-0000-0000-000000000001', 'Ref Admin'), ('a5800000-0000-0000-0000-000000000002', 'Asha Secret'),
  ('a5800000-0000-0000-0000-000000000003', 'Ravi'), ('a5800000-0000-0000-0000-000000000004', 'Outsider')
  on conflict (id) do update set name = excluded.name;
update public.profiles set is_admin = true where id = 'a5800000-0000-0000-0000-000000000001';
insert into public.care_circles (id, created_by) values ('c5800000-0000-0000-0000-0000000000c1', 'a5800000-0000-0000-0000-000000000002');
insert into public.care_circle_members (circle_id, user_id, role) values
  ('c5800000-0000-0000-0000-0000000000c1', 'a5800000-0000-0000-0000-000000000002', 'owner'),
  ('c5800000-0000-0000-0000-0000000000c1', 'a5800000-0000-0000-0000-000000000003', 'member')
  on conflict do nothing;
insert into public.care_recipients (circle_id, name, relation, whatsapp_phone, consent_at)
  values ('c5800000-0000-0000-0000-0000000000c1', 'Papa Secret', 'father', '+919999900581', now());

-- 1-4 the columns and their checks
select has_column('public', 'profiles', 'referred_by', 'profiles.referred_by exists');
select has_column('public', 'profiles', 'heard_from', 'profiles.heard_from exists');
select throws_ok($$update public.profiles set heard_from = 'facebook' where id = 'a5800000-0000-0000-0000-000000000004'$$,
  '23514', null, 'only the fixed answers are allowed');
select throws_ok($$update public.profiles set referred_by = 'D 001; drop' where id = 'a5800000-0000-0000-0000-000000000004'$$,
  '23514', null, 'a code with spaces or symbols is refused');

-- 5-10 Asha sets her own values; a later value never overwrites them
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5800000-0000-0000-0000-000000000002","role":"authenticated"}', true);
update public.profiles set referred_by = 'D001', heard_from = 'doctor' where id = 'a5800000-0000-0000-0000-000000000002';
update public.profiles set referred_by = 'D999', heard_from = 'google' where id = 'a5800000-0000-0000-0000-000000000002';
-- she cannot set them for her brother either (RLS: own row only)
update public.profiles set referred_by = 'D777' where id = 'a5800000-0000-0000-0000-000000000003';
select lives_ok($$select name, plan, language from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'$$,
  'the other profile columns can still be read');
select throws_ok($$select referred_by from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'$$,
  '42501', null, 'the code cannot be read through Supabase, not even her own');
select throws_ok($$select * from public.profiles$$, '42501', null, 'select * is refused too (it would include the code)');
reset role;
select set_config('request.jwt.claims', '', true);
select is((select referred_by from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'), 'D001', 'the first code wins');
select is((select heard_from from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'), 'doctor', 'the first answer wins');
select is((select referred_by from public.profiles where id = 'a5800000-0000-0000-0000-000000000003'), null, 'nobody sets another person''s code');

-- 11-13 her brother can see her name (same family) but not where she heard of Chhaon
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5800000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select name from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'), 'Asha Secret', 'family members still see each other''s names');
select throws_ok($$select heard_from from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'$$,
  '42501', null, 'a family member cannot read the answer');
select throws_ok($$select referred_by from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'$$,
  '42501', null, 'a family member cannot read the doctor code');

-- 14-15 the backend (database owner) also keeps the first value
reset role;
select set_config('request.jwt.claims', '', true);
update public.profiles set referred_by = 'D555', heard_from = null where id = 'a5800000-0000-0000-0000-000000000002';
select is((select referred_by || '/' || heard_from from public.profiles where id = 'a5800000-0000-0000-0000-000000000002'), 'D001/doctor',
  'not even the backend overwrites or clears them');
update public.profiles set heard_from = 'whatsapp' where id = 'a5800000-0000-0000-0000-000000000004';
select is((select heard_from from public.profiles where id = 'a5800000-0000-0000-0000-000000000004'), 'whatsapp', 'an empty answer can be set once');

-- 16-22 the admin's counts
select ok(not has_function_privilege('anon', 'public.admin_referral_stats()', 'execute'), 'anon cannot call the stats');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5800000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select throws_ok($$select public.admin_referral_stats()$$, '42501', null, 'a normal caregiver cannot read the counts');
select set_config('request.jwt.claims', '{"sub":"a5800000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select ok(public.admin_referral_stats() ?& array['total', 'with_code', 'by_code', 'by_heard_from'], 'the admin gets every section');
select is((select e from jsonb_array_elements(public.admin_referral_stats() -> 'by_code') e where e ->> 'code' = 'D001'),
  '{"code": "D001", "signups": 1, "with_person": 1, "agreed": 1}'::jsonb, 'D001 brought one family, set up and agreed');
select ok(not exists (select 1 from jsonb_array_elements(public.admin_referral_stats() -> 'by_code') e where e ->> 'code' in ('D999', 'D777', 'D555')),
  'codes that lost are not counted');
select cmp_ok((select (e ->> 'signups')::int from jsonb_array_elements(public.admin_referral_stats() -> 'by_heard_from') e where e ->> 'answer' = 'doctor'),
  '>=', 1, 'answers are counted');
select ok(public.admin_referral_stats()::text !~ '@|\+[0-9]{8}|Secret|Ravi|Outsider', 'no email, phone number or name in the counts');
reset role;

-- Guard for later migrations: the referral lock lists the readable profiles columns one by one, so a NEW profiles column is
-- unreadable for signed-in users until it is granted. This fails as soon as that happens (fix: grant select (<column>) on
-- public.profiles to authenticated, or run the lock block of 20261106000058_referral.sql again).
select is((select coalesce(string_agg(column_name, ', ' order by column_name), '') from information_schema.columns
            where table_schema = 'public' and table_name = 'profiles' and column_name not in ('referred_by', 'heard_from')
              and not has_column_privilege('authenticated', 'public.profiles', column_name, 'SELECT')),
  '', 'every profiles column except the two referral columns is readable by signed-in users');

select * from finish();
rollback;
