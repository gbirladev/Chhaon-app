-- Tablet colour + shape (migration 0042). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a5000000-0000-0000-0000-000000000001', 'pill-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b5000000-0000-0000-0000-000000000002', 'pill-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

create temp table t_pill (k text primary key, v text);
grant all on t_pill to authenticated;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('c5000000-0000-0000-0000-0000000000a1', 'a5000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation)
  values ('d5000000-0000-0000-0000-0000000000d1', 'c5000000-0000-0000-0000-0000000000a1', 'Ramesh', 'Papa', 'father');
insert into t_pill select 'med', public.create_medicine('d5000000-0000-0000-0000-0000000000d1', 'Amlodipine', '5 mg', null, null, 10, 2, array['08:00']::time[])::text;

select is((select pill_colour from public.medicines where id = (select v::uuid from t_pill where k = 'med')), null, 'a new medicine has no colour until one is chosen');
select lives_ok($$update public.medicines set pill_colour = 'blue', pill_shape = 'round' where id = (select v::uuid from t_pill where k = 'med')$$, 'the family sets colour and shape with a normal update');
select is((select pill_colour || ' ' || pill_shape from public.medicines where id = (select v::uuid from t_pill where k = 'med')), 'blue round', 'both are saved');
select throws_ok($$update public.medicines set pill_colour = 'Blue' where id = (select v::uuid from t_pill where k = 'med')$$, '23514', null, 'only the fixed colour words (lower case) are accepted');
select throws_ok($$update public.medicines set pill_shape = 'triangle' where id = (select v::uuid from t_pill where k = 'med')$$, '23514', null, 'an unknown shape is refused');
select throws_ok($$update public.medicines set pill_colour = E'blue\n*1*' where id = (select v::uuid from t_pill where k = 'med')$$, '23514', null, 'free text cannot reach the WhatsApp message');
select lives_ok($$update public.medicines set pill_colour = null, pill_shape = null where id = (select v::uuid from t_pill where k = 'med')$$, 'both can be cleared again');
select lives_ok($$update public.medicines set pill_colour = 'white', pill_shape = 'capsule' where id = (select v::uuid from t_pill where k = 'med')$$, 'set again for the stranger check');

-- someone outside the family circle cannot change it (the update matches no row)
select set_config('request.jwt.claims', '{"sub":"b5000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
update public.medicines set pill_colour = 'red' where id = (select v::uuid from t_pill where k = 'med');
reset role;
select is((select pill_colour from public.medicines where id = (select v::uuid from t_pill where k = 'med')), 'white', 'a stranger cannot change the colour');

select * from finish();
rollback;
