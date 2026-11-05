-- Care type + due date (migration 0043). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a6000000-0000-0000-0000-000000000001', 'care-type-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b6000000-0000-0000-0000-000000000002', 'care-type-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a6000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('c6000000-0000-0000-0000-0000000000a1', 'a6000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation)
  values ('d6000000-0000-0000-0000-0000000000d1', 'c6000000-0000-0000-0000-0000000000a1', 'Shivani', 'Shivani', 'spouse');

select is((select care_type || '|' || coalesce(due_date::text, '-') from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'),
  'older|-', 'a profile without a care type is an older parent with no due date (existing families are unchanged)');
select lives_ok($$update public.care_recipients set care_type = 'pregnant', due_date = current_date + 120 where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  'the family marks a pregnancy and sets the due date');
select is((select care_type from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), 'pregnant', 'the care type is saved');
select throws_ok($$update public.care_recipients set care_type = 'baby' where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  '23514', null, 'only older, pregnant or child are accepted');
select throws_ok($$update public.care_recipients set care_type = 'older' where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  '23514', null, 'a due date cannot stay on a profile that is no longer a pregnancy');
select lives_ok($$update public.care_recipients set care_type = 'older', due_date = null where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  'switching away from pregnant together with clearing the due date works');
select lives_ok($$update public.care_recipients set care_type = 'pregnant', due_date = current_date + 30 where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  'set again for the stranger check');

-- someone outside the family circle cannot change it (the update matches no row)
select set_config('request.jwt.claims', '{"sub":"b6000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
update public.care_recipients set due_date = current_date + 200 where id = 'd6000000-0000-0000-0000-0000000000d1';
reset role;
select is((select due_date from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), current_date + 30,
  'a stranger cannot change the due date');

select * from finish();
rollback;
