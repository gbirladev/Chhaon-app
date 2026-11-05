-- Key facts (migration 0052): a child's weight, and the pregnancy / child lines on the public emergency card.
-- Also: a medicine whose stock is not tracked (NULL) never raises a refill or out-of-stock alert. Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(22);

insert into auth.users (id, email, instance_id, aud, role) values
  ('aaaaaaaa-0000-0000-0000-0000000000f1', 'kf-alice@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000f1","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('cccccccc-0000-0000-0000-0000000000f1', 'aaaaaaaa-0000-0000-0000-0000000000f1');
insert into public.care_recipients (id, circle_id, name, timezone) values
  ('dddddddd-0000-0000-0000-0000000000f1', 'cccccccc-0000-0000-0000-0000000000f1', 'Meera Devi', 'Asia/Kolkata');
create temp table t (k text primary key, v text);
grant all on t to anon, authenticated;
insert into t select 'tok', token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000f1');
reset role;
create temp view c as select public.public_emergency_card((select v from t where k = 'tok')) as j;
grant select on c to anon;

-- ---------------------------------------------------------------- weight columns
select has_column('public', 'care_recipients', 'weight_kg', 'care_recipients.weight_kg exists');
select has_column('public', 'care_recipients', 'weight_on', 'care_recipients.weight_on exists');
select throws_ok($$update public.care_recipients set weight_kg = 0.4 where id = 'dddddddd-0000-0000-0000-0000000000f1'$$,
  '23514', null, 'a weight under 0.5 kg is refused');
select throws_ok($$update public.care_recipients set weight_kg = 200.5 where id = 'dddddddd-0000-0000-0000-0000000000f1'$$,
  '23514', null, 'a weight over 200 kg is refused');
select throws_ok($$update public.care_recipients set weight_kg = null, weight_on = current_date where id = 'dddddddd-0000-0000-0000-0000000000f1'$$,
  '23514', null, 'a date without a weight is refused');
select throws_ok($$update public.care_recipients set weight_kg = 7, weight_on = current_date + 5 where id = 'dddddddd-0000-0000-0000-0000000000f1'$$,
  '23514', null, 'a weight measured in the future is refused');
select lives_ok($$update public.care_recipients set weight_kg = 6.8, weight_on = current_date - 4 where id = 'dddddddd-0000-0000-0000-0000000000f1'$$,
  'a sane weight with its date is saved');

-- ---------------------------------------------------------------- an older parent: no new facts
select is((select j -> 'pregnancy' from c), 'null'::jsonb, 'an older parent has no pregnancy line');
select is((select j -> 'child' from c), 'null'::jsonb, 'an older parent has no child line, even with a weight saved');

-- ---------------------------------------------------------------- a pregnancy
update public.care_recipients set weight_kg = null, weight_on = null, care_type = 'pregnant',
  due_date = (now() at time zone 'Asia/Kolkata')::date + 112   -- 280 - 112 = 168 days = week 24
where id = 'dddddddd-0000-0000-0000-0000000000f1';
select is((select (j -> 'pregnancy' ->> 'week')::int from c), 24, 'pregnancy week counted back from the due date (completed weeks)');
select is((select j -> 'pregnancy' ->> 'due_date' from c), ((now() at time zone 'Asia/Kolkata')::date + 112)::text, 'the due date is on the card');
select is((select j -> 'child' from c), 'null'::jsonb, 'a pregnancy has no child line');
update public.care_recipients set due_date = (now() at time zone 'Asia/Kolkata')::date + 278 where id = 'dddddddd-0000-0000-0000-0000000000f1';
select is((select j -> 'pregnancy' -> 'week' from c), 'null'::jsonb, 'before week 1 the week is null (the card then shows only the due date)');
update public.care_recipients set due_date = null where id = 'dddddddd-0000-0000-0000-0000000000f1';
select is((select j -> 'pregnancy' from c), '{"week": null, "due_date": null}'::jsonb, 'a pregnancy without a due date still says pregnant');

-- ---------------------------------------------------------------- a child
update public.care_recipients set care_type = 'child', dob = (now() at time zone 'Asia/Kolkata')::date - 150, age = null,
  weight_kg = 6.8, weight_on = (now() at time zone 'Asia/Kolkata')::date - 4
where id = 'dddddddd-0000-0000-0000-0000000000f1';
select is((select j -> 'child' ->> 'born' from c), ((now() at time zone 'Asia/Kolkata')::date - 150)::text, 'a child''s date of birth is on the card');
select is((select (j -> 'child' ->> 'weight_kg')::numeric from c), 6.8::numeric, 'the recorded weight is on the card');
select is((select j -> 'child' ->> 'weight_on' from c), ((now() at time zone 'Asia/Kolkata')::date - 4)::text, 'with the day it was measured');
select is((select j -> 'age' from c), 'null'::jsonb, 'a child has no age in years (the date of birth says it)');
select is((select j -> 'pregnancy' from c), 'null'::jsonb, 'a child has no pregnancy line');
select is((select array_agg(k order by k) from jsonb_object_keys((select j from c)) k),
          array['age','allergies','blood_group','child','conditions','contacts','doctor','medicines','name','pregnancy'],
          'still only the minimal fields');

-- ---------------------------------------------------------------- stock not tracked: no stock alerts
insert into public.medicines (id, recipient_id, name, dosage, active, stock_count, refill_threshold) values
  ('eeeeeeee-0000-0000-0000-0000000000f1', 'dddddddd-0000-0000-0000-0000000000f1', 'Iron drops', '1 ml', true, null, 5);
select public.consume_stock('eeeeeeee-0000-0000-0000-0000000000f1', 1);
select is((select stock_count from public.medicines where id = 'eeeeeeee-0000-0000-0000-0000000000f1'), null::integer,
  'a dose taken never turns "not tracked" into 0');
select is((select count(*)::int from public.notifications where type in ('refill_needed', 'out_of_stock')
            and payload ->> 'medicine_id' = 'eeeeeeee-0000-0000-0000-0000000000f1'), 0,
  'no refill or out-of-stock alert for a medicine whose stock is not tracked');

select * from finish();
rollback;
