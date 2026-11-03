-- Pricing plans, the 30-day trial and their limits (migration 0027). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(37);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a7000000-0000-0000-0000-000000000001', 'free-expired@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b7000000-0000-0000-0000-000000000002', 'family-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c7000000-0000-0000-0000-000000000003', 'relative@test.dev',     '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('d7000000-0000-0000-0000-000000000004', 'new-trial@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('e7000000-0000-0000-0000-000000000005', 'plus-paid@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('f7000000-0000-0000-0000-000000000006', 'ultra-owner@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

-- a brand-new account starts a 30-day trial
select ok((select trial_ends_at between now() + interval '29 days' and now() + interval '31 days' from public.profiles where id = 'd7000000-0000-0000-0000-000000000004'), 'a new account gets a 30-day trial');

-- set up by hand, as the owner of the app would
-- New accounts start on Family (migration 0039). These tests are about Free and its trial, so say so.
update public.profiles set plan = 'free' where id in ('a7000000-0000-0000-0000-000000000001', 'c7000000-0000-0000-0000-000000000003', 'd7000000-0000-0000-0000-000000000004');
update public.profiles set trial_ends_at = now() - interval '1 day' where id in ('a7000000-0000-0000-0000-000000000001', 'b7000000-0000-0000-0000-000000000002', 'e7000000-0000-0000-0000-000000000005');
update public.profiles set plan = 'family' where id = 'b7000000-0000-0000-0000-000000000002';
update public.profiles set plan = 'plus'   where id = 'e7000000-0000-0000-0000-000000000005';
update public.profiles set plan = 'ultra'  where id = 'f7000000-0000-0000-0000-000000000006';

create temp table t_ids (k text primary key, v uuid);
grant all on t_ids to authenticated;
create temp table t_tok (k text primary key, v text);
grant all on t_tok to authenticated;

-- ============ Free (trial over): 1 parent, 2 medicines, just you
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a7000000-0000-0000-0000-000000000001","role":"authenticated"}', true);

select is((public.plan_overview() ->> 'effective') || ',' || (public.plan_overview() ->> 'on_trial'), 'free,false', 'after the trial a Free account is on Free');
insert into t_ids select 'free_parent', public.create_care_recipient('{"name":"Papa","relation":"father","caregiver_attested":true}'::jsonb);
select is((public.plan_overview() ->> 'parents_used')::int, 1, 'plan_overview counts my parents');
select throws_ok($$select public.create_care_recipient('{"name":"Mummy","relation":"mother"}'::jsonb)$$, 'P0001', null, 'Free allows only one parent');
select lives_ok($$select public.create_medicine((select v from t_ids where k = 'free_parent'), 'M' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 2) i$$, 'Free allows 2 medicines');
select throws_ok($$select public.create_medicine((select v from t_ids where k = 'free_parent'), 'Third', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the 3rd medicine is refused on Free');
select throws_ok($$select public.create_family_invite((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'free_parent')))$$, 'P0001', null, 'a Free circle (just the owner) cannot invite anyone');

-- nobody upgrades themselves or extends the trial
select throws_ok($$update public.profiles set plan = 'family' where id = 'a7000000-0000-0000-0000-000000000001'$$, '42501', null, 'a caregiver cannot change their own plan');
select throws_ok($$update public.profiles set trial_ends_at = now() + interval '1 year' where id = 'a7000000-0000-0000-0000-000000000001'$$, '42501', null, 'a caregiver cannot extend their own trial');

-- the limits cannot be skipped by writing to the tables directly
insert into public.care_circles (id, created_by) values ('c7000000-0000-0000-0000-0000000000c1', 'a7000000-0000-0000-0000-000000000001');
select throws_ok($$insert into public.care_recipients (circle_id, name, relation) values ('c7000000-0000-0000-0000-0000000000c1', 'Direct', 'other')$$, 'P0001', null, 'a second parent is refused even as a direct insert');
select throws_ok($$insert into public.medicines (recipient_id, name) values ((select v from t_ids where k = 'free_parent'), 'Direct medicine')$$, 'P0001', null, 'a third medicine is refused even as a direct insert');
select throws_ok($$insert into public.care_circle_members (circle_id, user_id, role) values ((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'free_parent')), 'c7000000-0000-0000-0000-000000000003', 'member')$$, null, null, 'nobody can add a member to a circle by writing the table directly');

-- ============ New account on trial: gets Plus (1 parent, 5 people, 10 medicines)
select set_config('request.jwt.claims', '{"sub":"d7000000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select is((public.plan_overview() ->> 'effective') || ',' || (public.plan_overview() ->> 'on_trial'), 'plus,true', 'during the trial a Free account gets the Plus limits');
insert into t_ids select 'trial_parent', public.create_care_recipient('{"name":"Trial 1","relation":"other"}'::jsonb);
select throws_ok($$select public.create_care_recipient('{"name":"Trial 2","relation":"other"}'::jsonb)$$, 'P0001', null, 'Plus (and the trial) allows one parent');
select lives_ok($$select public.create_medicine((select v from t_ids where k = 'trial_parent'), 'T' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 10) i$$, 'the trial allows 10 medicines');
select throws_ok($$select public.create_medicine((select v from t_ids where k = 'trial_parent'), 'T11', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the 11th medicine is refused');
select is((public.plan_overview() ->> 'would_pause_medicines')::int, 8, 'during the trial the family is told how many medicines the Free plan would pause');
insert into t_tok select 'tinv', public.create_family_invite((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'trial_parent')));
select set_config('request.jwt.claims', '{"sub":"c7000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select lives_ok($$select public.accept_family_invite((select v from t_tok where k = 'tinv'))$$, 'a relative joins during the trial');

-- the trial ends: nothing is deleted, but nothing new can be added
reset role;
select set_config('request.jwt.claims', '', true);
update public.profiles set trial_ends_at = now() - interval '1 minute' where id = 'd7000000-0000-0000-0000-000000000004';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"d7000000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select is((public.plan_overview() ->> 'effective') || ',' || (public.plan_overview() ->> 'on_trial'), 'free,false', 'when the trial ends the account is back on Free');
select is((select count(*)::int from public.medicines where recipient_id = (select v from t_ids where k = 'trial_parent')), 10, 'existing medicines are kept');
select throws_ok($$select public.create_medicine((select v from t_ids where k = 'trial_parent'), 'After trial', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'no new medicine once the trial is over');
select throws_ok($$select public.create_family_invite((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'trial_parent')))$$, 'P0001', null, 'no new invite once the trial is over');
-- the 8 medicines above the Free limit are paused, and the whole family can see that (not strangers)
select is(jsonb_array_length(public.plan_pauses() -> 'medicines'), 8, 'the owner sees the paused medicines');
select set_config('request.jwt.claims', '{"sub":"c7000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(jsonb_array_length(public.plan_pauses() -> 'medicines'), 8, 'a family member sees them too');
select set_config('request.jwt.claims', '{"sub":"a7000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(jsonb_array_length(public.plan_pauses() -> 'medicines'), 0, 'someone outside the circle sees nothing');
select set_config('request.jwt.claims', '{"sub":"d7000000-0000-0000-0000-000000000004","role":"authenticated"}', true);

-- ============ Plus (paid): 1 parent, and the trial ending changes nothing
select set_config('request.jwt.claims', '{"sub":"e7000000-0000-0000-0000-000000000005","role":"authenticated"}', true);
select is((public.plan_overview() ->> 'effective'), 'plus', 'a paid Plus account stays on Plus after the trial date');
insert into t_ids select 'plus_parent', public.create_care_recipient('{"name":"Plus 1","relation":"other"}'::jsonb);
select throws_ok($$select public.create_care_recipient('{"name":"Plus 2","relation":"other"}'::jsonb)$$, 'P0001', null, 'Plus allows one parent');

-- ============ Family: four parents
select set_config('request.jwt.claims', '{"sub":"b7000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$select public.create_care_recipient(('{"name":"Parent ' || i || '","relation":"other"}')::jsonb) from generate_series(1, 4) i$$, 'Family allows four parents');
select throws_ok($$select public.create_care_recipient('{"name":"Fifth","relation":"other"}'::jsonb)$$, 'P0001', null, 'a fifth parent is refused on Family');

-- ============ Family: 20 medicines per parent
select set_config('request.jwt.claims', '{"sub":"b7000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$select public.create_medicine((select id from public.care_recipients where name = 'Parent 1' and circle_id in (select id from public.care_circles where created_by = 'b7000000-0000-0000-0000-000000000002')), 'F' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 20) i$$, 'Family allows 20 medicines');
select throws_ok($$select public.create_medicine((select id from public.care_recipients where name = 'Parent 1' and circle_id in (select id from public.care_circles where created_by = 'b7000000-0000-0000-0000-000000000002')), 'F21', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the 21st medicine is refused on Family');

-- ============ Ultra: no plan limit, only the system ceiling (20 parents, 100 medicines, 50 people)
select set_config('request.jwt.claims', '{"sub":"f7000000-0000-0000-0000-000000000006","role":"authenticated"}', true);
select is((public.plan_overview() ->> 'effective') || ',' || coalesce(public.plan_overview() ->> 'max_parents', 'unlimited'), 'ultra,unlimited', 'Ultra reports no plan limit');
select lives_ok($$select public.create_care_recipient(('{"name":"Max ' || i || '","relation":"other"}')::jsonb) from generate_series(1, 20) i$$, 'Ultra allows many parents (more than any other plan)');
select throws_ok($$select public.create_care_recipient('{"name":"Max 21","relation":"other"}'::jsonb)$$, 'P0001', null, 'but not more than the system ceiling of 20');
select lives_ok($$select public.create_medicine((select id from public.care_recipients where name = 'Max 1'), 'X' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 100) i$$, 'Ultra allows 100 medicines');
select throws_ok($$select public.create_medicine((select id from public.care_recipients where name = 'Max 1'), 'X101', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'but not more than the system ceiling of 100');
-- 50 people: fill an Ultra circle with 49 more members (as the owner of the app), then the next invite is refused
reset role;
select set_config('request.jwt.claims', '', true);
insert into auth.users (id, email, instance_id, aud, role)
  select gen_random_uuid(), 'bulk-' || i || '@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated' from generate_series(1, 49) i;
insert into public.care_circle_members (circle_id, user_id, role)
  select (select circle_id from public.care_recipients where name = 'Max 1'), id, 'member' from auth.users where email like 'bulk-%@test.dev';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"f7000000-0000-0000-0000-000000000006","role":"authenticated"}', true);
select throws_ok($$select public.create_family_invite((select circle_id from public.care_recipients where name = 'Max 1'))$$, 'P0001', null, 'a circle of 50 is full even on Ultra');

select * from finish();
rollback;
