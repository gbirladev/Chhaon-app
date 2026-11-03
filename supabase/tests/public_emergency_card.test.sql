-- Public emergency card served by the database (migration 0038). Run with: npx supabase test db
-- The table constraints already refuse most awkward data (8 blood groups, 30 conditions, no control characters in names),
-- so the cleaning is tested on the helper directly, and the card on data the database allows.
begin;
create extension if not exists pgtap with schema extensions;
select plan(40);

insert into auth.users (id, email, instance_id, aud, role) values
  ('aaaaaaaa-0000-0000-0000-0000000000c1', 'pc-alice@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set name = 'Alice Sharma', phone = '+919800000001' where id = 'aaaaaaaa-0000-0000-0000-0000000000c1';

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000c1","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('cccccccc-0000-0000-0000-0000000000c1', 'aaaaaaaa-0000-0000-0000-0000000000c1');
insert into public.care_recipients (id, circle_id, name) values ('dddddddd-0000-0000-0000-0000000000c1', 'cccccccc-0000-0000-0000-0000000000c1', 'Papa');
create temp table t (k text primary key, v text);
grant all on t to anon, authenticated;
insert into t select 'tok', token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000c1');
reset role;

update public.care_recipients set
  name = 'Ramesh Kumar Sharma',
  age = null, dob = (now() at time zone 'utc')::date - interval '60 years 1 day',
  blood_group = 'O+', city = 'Indore', whatsapp_phone = '+919999999999',
  conditions = array(select 'Condition ' || g from generate_series(1, 30) g),
  allergies = array['Penicillin', 'Sulfa drugs'],
  emergency_contacts = '[{"name":"Neighbour","phone":"+919822200123"},{"name":"Bad number","phone":"12345"},{"name":"Text phone","phone":"call me"}]'::jsonb,
  doctor_contacts = '[{"phone":"nope"},{"name":"  "},{"name":"Dr. Anil Mehta"},{"name":"Dr. Second"}]'::jsonb
where id = 'dddddddd-0000-0000-0000-0000000000c1';

insert into public.medicines (id, recipient_id, name, dosage, active) values
  ('eeeeeeee-0000-0000-0000-0000000000c1', 'dddddddd-0000-0000-0000-0000000000c1', 'Telma 40', '40 mg', true),
  ('eeeeeeee-0000-0000-0000-0000000000c2', 'dddddddd-0000-0000-0000-0000000000c1', 'Stopped pill', '1 tablet', false);
insert into public.medicine_schedules (medicine_id, time_of_day, start_date, end_date) values
  ('eeeeeeee-0000-0000-0000-0000000000c1', '20:00', (now() at time zone 'utc')::date - 30, null),
  ('eeeeeeee-0000-0000-0000-0000000000c1', '08:00', (now() at time zone 'utc')::date - 30, null),
  ('eeeeeeee-0000-0000-0000-0000000000c1', '13:00', (now() at time zone 'utc')::date - 30, (now() at time zone 'utc')::date - 1);   -- ended yesterday: must not show
create temp view c as select public.public_emergency_card((select v from t where k = 'tok')) as j;
grant select on c to anon;

-- shape and privacy
select isnt((select j from c), null, 'a valid token returns a card');
select is((select array_agg(k order by k) from jsonb_object_keys((select j from c)) k),
          array['age','allergies','blood_group','conditions','contacts','doctor','medicines','name'],
          'only the minimal fields: no city, date of birth, phone, stock, ids');
select is((select j ->> 'name' from c), 'Ramesh', 'first name only');
select ok((select j::text from c) !~ '(Indore|9999999999|Kumar)', 'no city, no parent phone, no surname in the card');
select is((select (j -> 'age')::int from c), 60, 'age is worked out from the date of birth when no age is stored');
update public.care_recipients set age = 70 where id = 'dddddddd-0000-0000-0000-0000000000c1';
select is((select (j -> 'age')::int from c), 70, 'a stored age wins');
select is((select j ->> 'blood_group' from c), 'O+', 'blood group is shown');
update public.care_recipients set blood_group = null where id = 'dddddddd-0000-0000-0000-0000000000c1';
select is((select j -> 'blood_group' from c), 'null'::jsonb, 'no blood group is null, not an empty string');
select is((select jsonb_array_length(j -> 'conditions') from c), 30, 'all 30 conditions are kept');
select is((select j -> 'allergies' ->> 1 from c), 'Sulfa drugs', 'allergies are listed');

-- medicines
select is((select jsonb_array_length(j -> 'medicines') from c), 1, 'only active medicines');
select is((select j -> 'medicines' -> 0 ->> 'name' from c), 'Telma 40', 'medicine name and dose');
select is((select j -> 'medicines' -> 0 -> 'times' from c), '["08:00","20:00"]'::jsonb, 'times are sorted, and a schedule that ended is left out');

-- contacts and doctor
select is((select j -> 'contacts' -> 0 ->> 'phone' from c), '+919822200123', 'a listed emergency contact comes first');
select is((select jsonb_array_length(j -> 'contacts') from c), 2, 'bad numbers are dropped; the family caregiver phone is added');
select is((select j -> 'contacts' -> 1 ->> 'phone' from c), '+919800000001', 'the caregiver phone is the second contact');
update public.care_recipients set emergency_contacts = '[{"name":"Alice","phone":"+919800000001"}]'::jsonb where id = 'dddddddd-0000-0000-0000-0000000000c1';
select is((select jsonb_array_length(j -> 'contacts') from c), 1, 'a family phone already listed is not repeated');
update public.care_recipients set emergency_contacts = '[{"name":"A","phone":"+911111111111"},{"name":"B","phone":"+912222222222"},{"name":"C","phone":"+913333333333"},{"name":"D","phone":"+914444444444"}]'::jsonb where id = 'dddddddd-0000-0000-0000-0000000000c1';
select is((select jsonb_array_length(j -> 'contacts') from c), 4, 'at most 4 contacts');
select is((select j ->> 'doctor' from c), 'Dr. Anil Mehta', 'the first doctor with a name');

-- view counter
create temp table vc (n integer);
insert into vc select view_count from public.emergency_links where active and recipient_id = 'dddddddd-0000-0000-0000-0000000000c1';
do $$ begin perform public.public_emergency_card((select v from t where k = 'tok')); end $$;
select is((select view_count from public.emergency_links where active and recipient_id = 'dddddddd-0000-0000-0000-0000000000c1'),
          (select n from vc) + 1, 'one view adds exactly one to the counter');
select isnt((select last_viewed_at from public.emergency_links where active and recipient_id = 'dddddddd-0000-0000-0000-0000000000c1'), null, 'the last view time is recorded');

-- anything else is the same nothing
select is(public.public_emergency_card(repeat('f', 64)), null, 'unknown token gives null');
select is(public.public_emergency_card('not-a-token'), null, 'malformed token gives null');
select is(public.public_emergency_card('../etc/passwd'), null, 'a path-like token gives null');
select is(public.public_emergency_card(null), null, 'null gives null');
select isnt(public.public_emergency_card(' ' || upper((select v from t where k = 'tok')) || ' '), null, 'a pasted token with capitals and spaces still works');

-- rotate, revoke, delete
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000c1","role":"authenticated"}', true);
select public.rotate_emergency_link('dddddddd-0000-0000-0000-0000000000c1');
reset role;
select is((select j from c), null, 'the old token stops working after a rotation');
insert into t select 'tok2', token from public.emergency_links where active and recipient_id = 'dddddddd-0000-0000-0000-0000000000c1';
select isnt(public.public_emergency_card((select v from t where k = 'tok2')), null, 'the new token works');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000c1","role":"authenticated"}', true);
select public.revoke_emergency_link('dddddddd-0000-0000-0000-0000000000c1');
reset role;
select is(public.public_emergency_card((select v from t where k = 'tok2')), null, 'a revoked link stops working at once');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000c1","role":"authenticated"}', true);
insert into t select 'tok3', token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000c1');
reset role;
delete from public.care_recipients where id = 'dddddddd-0000-0000-0000-0000000000c1';
select is(public.public_emergency_card((select v from t where k = 'tok3')), null, 'a deleted parent gives null');

-- the cleaning helper, on strings the table rules would normally stop
select is(public.card_clean('Telma' || chr(8238) || chr(8203) || ' 40'), 'Telma 40', 'right-to-left override and zero-width space are removed');
select is(public.card_clean(E'a\n\n\tb'), 'a b', 'control characters become one space');
select is(public.card_clean(chr(160) || '  Papa ' || chr(8195)), 'Papa', 'Unicode spaces are trimmed');
select is(public.card_clean('abcdefghij', 5), 'abcde', 'cut to the length');
select is(public.card_clean('क' || chr(2381) || chr(8205) || 'ष'), 'क' || chr(2381) || chr(8205) || 'ष', 'the Hindi joiner is kept');
select is(public.card_clean(null), '', 'null becomes an empty string');

-- what the anonymous role may and may not do (a link must exist for the "sees no rows" check to mean something)
insert into public.care_recipients (id, circle_id, name) values ('dddddddd-0000-0000-0000-0000000000c2', 'cccccccc-0000-0000-0000-0000000000c1', 'Mummy');
insert into public.emergency_links (recipient_id) values ('dddddddd-0000-0000-0000-0000000000c2') on conflict do nothing;
set local role anon;
select lives_ok($$select public.public_emergency_card('f' || repeat('0', 63))$$, 'anon can call the card function');
select is((select count(*)::int from public.emergency_links), 0, 'anon sees no rows of the links table (row-level rules)');
select throws_ok($$select public.record_emergency_view('dddddddd-0000-0000-0000-0000000000c1')$$, '42501', null, 'anon cannot touch the view counter');
select throws_ok($$select public.card_clean('x')$$, '42501', null, 'anon cannot call the cleaning helper directly');
reset role;

select * from finish();
rollback;
