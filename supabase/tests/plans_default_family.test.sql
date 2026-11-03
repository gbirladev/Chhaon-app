-- Everyone on Family by default, admins always Ultra (migration 0039). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9000000-0000-0000-0000-000000000001', 'fam-new@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9000000-0000-0000-0000-000000000002', 'fam-old-free@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9000000-0000-0000-0000-000000000003', 'fam-old-plus@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('d9000000-0000-0000-0000-000000000004', 'fam-old-ultra@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('e9000000-0000-0000-0000-000000000005', 'fam-admin@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

-- ===== a brand-new account is on Family, even after its trial date has passed
select is((select plan from public.profiles where id = 'a9000000-0000-0000-0000-000000000001'), 'family', 'a new account starts on Family');
update public.profiles set trial_ends_at = now() - interval '1 day' where id = 'a9000000-0000-0000-0000-000000000001';

create temp table t_ids (k text primary key, v uuid);
grant all on t_ids to authenticated;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is((public.plan_overview() ->> 'effective') || ',' || (public.plan_overview() ->> 'on_trial'), 'family,false', 'after the trial date it is still Family, not Free');
select lives_ok($$select public.create_care_recipient(('{"name":"Parent ' || i || '","relation":"father","caregiver_attested":true}')::jsonb) from generate_series(1, 4) i$$, 'Family allows 4 parents');
select throws_ok($$select public.create_care_recipient('{"name":"Fifth","relation":"mother"}'::jsonb)$$, 'P0001', null, 'the 5th parent is refused');
insert into t_ids select 'p1', id from public.care_recipients where name = 'Parent 1';
select lives_ok($$select public.create_medicine((select v from t_ids where k = 'p1'), 'M' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 20) i$$, 'Family allows 20 medicines for one parent');
select throws_ok($$select public.create_medicine((select v from t_ids where k = 'p1'), 'Twenty-first', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the 21st medicine is refused');
select lives_ok($$select public.create_family_invite((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'p1')))$$, 'the family can invite relatives');
select is(public.plan_pauses(), '{"parents":[],"medicines":[]}'::jsonb, 'nothing is paused');
reset role;
select set_config('request.jwt.claims', '', true);   -- back to the database owner: no login claim

-- ===== the one-time move (the same statement as in the migration)
update public.profiles set plan = 'free'  where id = 'b9000000-0000-0000-0000-000000000002';
update public.profiles set plan = 'plus'  where id = 'c9000000-0000-0000-0000-000000000003';
update public.profiles set plan = 'ultra' where id = 'd9000000-0000-0000-0000-000000000004';
update public.profiles set plan = 'family' where plan in ('free', 'plus') and id in ('b9000000-0000-0000-0000-000000000002', 'c9000000-0000-0000-0000-000000000003', 'd9000000-0000-0000-0000-000000000004');
select is((select plan from public.profiles where id = 'b9000000-0000-0000-0000-000000000002'), 'family', 'a Free account moves up to Family');
select is((select plan from public.profiles where id = 'c9000000-0000-0000-0000-000000000003'), 'family', 'a Plus account moves up to Family');
select is((select plan from public.profiles where id = 'd9000000-0000-0000-0000-000000000004'), 'ultra', 'an Ultra account stays Ultra');

-- ===== admins are always unlimited
select is((select plan from public.profiles where id = 'e9000000-0000-0000-0000-000000000005'), 'family', 'before: the future admin is on Family');
update public.profiles set is_admin = true where id = 'e9000000-0000-0000-0000-000000000005';
select is((select plan from public.profiles where id = 'e9000000-0000-0000-0000-000000000005'), 'ultra', 'marking someone admin puts them on Ultra');
update public.profiles set plan = 'family' where id = 'e9000000-0000-0000-0000-000000000005';
select is((select plan from public.profiles where id = 'e9000000-0000-0000-0000-000000000005'), 'ultra', 'a hand edit cannot put an admin on a smaller plan');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"e9000000-0000-0000-0000-000000000005","role":"authenticated"}', true);
select lives_ok($$select public.create_care_recipient(('{"name":"Admin parent ' || i || '","relation":"father","caregiver_attested":true}')::jsonb) from generate_series(1, 6) i$$, 'an admin can add more than 4 parents');
select is(public.plan_overview() ->> 'effective', 'ultra', 'an admin is on the unlimited plan');
reset role;
select set_config('request.jwt.claims', '', true);   -- back to the database owner: no login claim
update public.profiles set is_admin = false where id = 'e9000000-0000-0000-0000-000000000005';
update public.profiles set plan = 'family' where id = 'e9000000-0000-0000-0000-000000000005';
select is((select plan from public.profiles where id = 'e9000000-0000-0000-0000-000000000005'), 'family', 'after the admin flag is removed the plan can be changed again');

select * from finish();
rollback;
