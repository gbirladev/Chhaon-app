-- "Your doctors" on the public emergency card (migration 0059). Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

insert into auth.users (id, email, instance_id, aud, role) values
  ('aaaaaaaa-0000-0000-0000-0000000005d1', 'yd-alice@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000005d1","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('cccccccc-0000-0000-0000-0000000005d1', 'aaaaaaaa-0000-0000-0000-0000000005d1');
insert into public.care_recipients (id, circle_id, name) values ('dddddddd-0000-0000-0000-0000000005d1', 'cccccccc-0000-0000-0000-0000000005d1', 'Papa');
create temp table t (k text primary key, v text);
grant all on t to anon, authenticated;
insert into t select 'tok', token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000005d1');
reset role;

update public.care_recipients set doctor_contacts = '[
  {"name":"Dr Anil Mehta","speciality":"Heart","clinic":"City Clinic","phone":"+919999900001","address":"12 MG Road, Indore"},
  {"name":"  ","phone":"+919999900002"},
  "Dr Old String",
  {"name":"Dr Rao","phone":"12345","speciality":"  "},
  {"name":"Dr Four"}, {"name":"Dr Five"}, {"name":"Dr Six"}
]'::jsonb where id = 'dddddddd-0000-0000-0000-0000000005d1';

create temp view d as select public.public_emergency_doctors((select v from t where k = 'tok')) as j;
grant select on d to anon;

-- grants: anon may call it (the public page); the table stays closed
select ok(has_function_privilege('anon', 'public.public_emergency_doctors(text)', 'execute'), 'the public page (anon) may call it');

set local role anon;
select is((select count(*)::int from public.care_recipients where id = 'dddddddd-0000-0000-0000-0000000005d1'), 0, 'anon still cannot read the row itself (RLS)');
select is((select jsonb_array_length(j) from d), 5, 'at most 5 doctors, rows without a name left out');
select is((select j -> 0 ->> 'name' from d), 'Dr Anil Mehta', 'the first doctor first');
select is((select j -> 0 ->> 'speciality' from d), 'Heart', 'the speciality is shown');
select is((select j -> 0 ->> 'phone' from d), '+919999900001', 'a valid phone is shown');
select is((select array_agg(k order by k) from jsonb_object_keys((select j -> 0 from d)) k), array['name','phone','speciality'],
          'only name, speciality and phone: no clinic, no address');
select ok((select j::text from d) !~ '(City Clinic|MG Road|9999900002)', 'no clinic, no address, no phone of a nameless row');
select is((select j -> 1 ->> 'name' from d), 'Dr Old String', 'an old plain-string entry still shows its name');
select is((select j -> 2 -> 'phone' from d), 'null'::jsonb, 'a bad phone is left out');
select is((select j -> 2 -> 'speciality' from d), 'null'::jsonb, 'a blank speciality is null');
reset role;

-- the same nothing for every bad link
select is(public.public_emergency_doctors(null), null, 'null token');
select is(public.public_emergency_doctors('not-a-token'), null, 'malformed token');
select is(public.public_emergency_doctors(repeat('a', 64)), null, 'unknown token');
update public.emergency_links set active = false where recipient_id = 'dddddddd-0000-0000-0000-0000000005d1';
select is((select j from d), null, 'a revoked link');

-- no doctors: an empty list, not null
update public.emergency_links set active = true where recipient_id = 'dddddddd-0000-0000-0000-0000000005d1';
update public.care_recipients set doctor_contacts = '[]'::jsonb where id = 'dddddddd-0000-0000-0000-0000000005d1';
select is((select j from d), '[]'::jsonb, 'no doctors is an empty list');

select * from finish();
rollback;
