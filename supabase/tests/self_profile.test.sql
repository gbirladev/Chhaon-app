-- "Me": a caregiver's own profile (migration 0036). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(22);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9200000-0000-0000-0000-000000000001', 'self-owner@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9200000-0000-0000-0000-000000000002', 'self-sibling@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set trial_ends_at = now() - interval '1 day', plan = 'free' where id in ('a9200000-0000-0000-0000-000000000001', 'b9200000-0000-0000-0000-000000000002');

create temp table t_ids (k text primary key, v uuid);
grant all on t_ids to authenticated;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9200000-0000-0000-0000-000000000001","role":"authenticated"}', true);

-- Free allows one parent. An own profile is extra.
insert into t_ids select 'parent', public.create_care_recipient('{"name":"Papa","relation":"father","caregiver_attested":true}'::jsonb);
select lives_ok($$insert into t_ids select 'me', public.create_care_recipient('{"name":"Ananya","is_self":true,"caregiver_attested":true,"whatsapp_phone":"+919810099999"}'::jsonb)$$, 'a Free account can add itself on top of its one parent');
select is((select relation || ',' || is_self::text from public.care_recipients where id = (select v from t_ids where k = 'me')), 'self,true', 'it is marked as an own profile (relation self)');
select is((public.plan_overview() ->> 'parents_used')::int, 1, 'plan_overview counts only the real parents');
select throws_ok($$select public.create_care_recipient('{"name":"Mummy","relation":"mother"}'::jsonb)$$, 'P0001', null, 'the parent limit still applies to parents');
select throws_ok($$select public.create_care_recipient('{"name":"Ananya again","is_self":true}'::jsonb)$$, 'P0001', 'self_exists', 'only one own profile per person');

-- it keeps the plan's medicine limit per profile (Free: 2)
select lives_ok($$select public.create_medicine((select v from t_ids where k = 'me'), 'M' || i, null, null, null, 10, 2, array['08:00']::time[]) from generate_series(1, 2) i$$, 'two own medicines are fine on Free');
select throws_ok($$select public.create_medicine((select v from t_ids where k = 'me'), 'Third', null, null, null, 10, 2, array['08:00']::time[])$$, 'P0001', null, 'the third is refused, like for a parent');

-- never paused as a parent
reset role;
select is((select count(*)::int from public.paused_recipients() where recipient_id = (select v from t_ids where k = 'me')), 0, 'an own profile is never paused as an extra parent');

-- private to its owner
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9200000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.create_family_invite((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'me')))$$, 'P0001', null, 'nobody can be invited to an own profile');
reset role;
select throws_ok($$insert into public.care_circle_members (circle_id, user_id, role) values ((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'me')), 'b9200000-0000-0000-0000-000000000002', 'member')$$, 'P0001', 'self_circle_private', 'and nobody can be added to its circle directly');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b9200000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is((select count(*)::int from public.care_recipients where is_self), 0, 'another family member cannot see it');
select is((select count(*)::int from public.medicines where recipient_id = (select v from t_ids where k = 'me')), 0, 'or its medicines');
select set_config('request.jwt.claims', '{"sub":"a9200000-0000-0000-0000-000000000001","role":"authenticated"}', true);

-- it cannot be turned into a parent profile or back
select throws_ok($$update public.care_recipients set is_self = false where id = (select v from t_ids where k = 'me')$$, '42501', null, 'an own profile cannot become a parent profile');
select throws_ok($$update public.care_recipients set is_self = true where id = (select v from t_ids where k = 'parent')$$, '42501', null, 'and a parent cannot become an own profile');
select throws_ok($$update public.care_recipients set circle_id = (select circle_id from public.care_recipients where id = (select v from t_ids where k = 'parent')) where id = (select v from t_ids where k = 'me')$$, '42501', null, 'or be moved into another circle');

-- love messages are for parents
select throws_ok($$select public.set_love_enabled((select v from t_ids where k = 'me'), true)$$, 'P0001', 'love_not_available', 'love messages are not available for an own profile');
select lives_ok($$select public.set_love_enabled((select v from t_ids where k = 'parent'), true)$$, 'but are for parents');

-- a parent profile cannot be added into the circle of an own profile
reset role;
select throws_ok($$insert into public.care_recipients (circle_id, name, relation) values ((select circle_id from public.care_recipients where id = (select v from t_ids where k = 'me')), 'Extra', 'other')$$, 'P0001', 'self_circle_private', 'a parent cannot be added to the circle of an own profile');

-- the same number on two profiles of yours: refused when one of them is your own
select throws_ok($$select public.create_care_recipient('{"name":"Mummy","relation":"mother","whatsapp_phone":"+919810099999"}'::jsonb)$$, 'P0001', 'self_number_in_use', 'a parent cannot be given your own number');
select lives_ok($$update public.care_recipients set whatsapp_phone = '+919810011111' where id = (select v from t_ids where k = 'parent')$$, 'a different number is fine');
select throws_ok($$update public.care_recipients set whatsapp_phone = '+919810099999' where id = (select v from t_ids where k = 'parent')$$, 'P0001', 'self_number_in_use', 'a parent cannot be changed to your own number');
select throws_ok($$update public.care_recipients set whatsapp_phone = '+919810011111' where id = (select v from t_ids where k = 'me')$$, 'P0001', 'self_number_in_use', 'your own number cannot be changed to a parent''s number');

select * from finish();
rollback;
