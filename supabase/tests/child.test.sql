-- Child profiles, database (migration 0045): guardian confirmation before vaccine rows, family-circle-only access, permissions.
-- Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9450000-0000-0000-0000-000000000001', 'child-mother@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9450000-0000-0000-0000-000000000002', 'child-nani@test.dev',     '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9450000-0000-0000-0000-000000000003', 'child-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.care_circles (id, created_by) values ('c0000000-0000-0000-0000-0000000945f1', 'a9450000-0000-0000-0000-000000000001');
insert into public.care_circle_members (circle_id, user_id, role) values ('c0000000-0000-0000-0000-0000000945f1', 'b9450000-0000-0000-0000-000000000002', 'member');
insert into public.care_recipients (id, circle_id, name, relation, dob) values
  ('d0000000-0000-0000-0000-0000000945f1', 'c0000000-0000-0000-0000-0000000945f1', 'Aarav', 'other', current_date - 30);

-- ============ the columns and the sanity rule
select has_column('public', 'care_recipients', 'guardian_confirmed_at', 'guardian confirmation column exists');
select has_table('public', 'child_vaccines', 'vaccine plan table exists');
select throws_ok($$update public.care_recipients set dob = current_date + 10 where id = 'd0000000-0000-0000-0000-0000000945f1'$$,
  '23514', null, 'a date of birth in the future is refused');

-- ============ no rows before the guardian confirmation
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9450000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$insert into public.child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date)
  values ('d0000000-0000-0000-0000-0000000945f1', 'bcg', 'bcg', '', 'IAP-ACVIP 2023', current_date - 30, current_date - 30)$$,
  '42501', null, 'no vaccine row before "I am the parent or legal guardian"');
select is(public.can_write_child_data('d0000000-0000-0000-0000-0000000945f1'), false, 'writing is not allowed yet');

update public.care_recipients set guardian_confirmed_at = now() where id = 'd0000000-0000-0000-0000-0000000945f1';
select is(public.can_write_child_data('d0000000-0000-0000-0000-0000000945f1'), true, 'after the confirmation it is');
select lives_ok($$insert into public.child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date)
  values ('d0000000-0000-0000-0000-0000000945f1', 'bcg', 'bcg', '', 'IAP-ACVIP 2023', current_date - 30, current_date - 30)$$, 'the mother adds a dose');
select throws_ok($$insert into public.child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date)
  values ('d0000000-0000-0000-0000-0000000945f1', 'bcg', 'bcg', '', 'IAP-ACVIP 2023', current_date, current_date)$$, '23505', null, 'one row per dose');
select throws_ok($$update public.child_vaccines set given_on = current_date, not_given = true$$, '23514', null, 'given and not given at once is refused');
select throws_ok($$update public.child_vaccines set reminded_due_date = current_date$$, '42501', null, 'the reminder bookkeeping is backend only');

-- ============ the family member (Nani) reads and marks it
select set_config('request.jwt.claims', '{"sub":"b9450000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is((select count(*)::int from public.child_vaccines), 1, 'a family member sees the plan');
select lives_ok($$update public.child_vaccines set given_on = current_date - 29$$, 'and marks a dose given');

-- ============ a stranger sees and changes nothing
select set_config('request.jwt.claims', '{"sub":"c9450000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.child_vaccines), 0, 'a stranger sees no vaccine rows');
update public.child_vaccines set notes = 'x';
reset role;
select is((select notes from public.child_vaccines), null, 'and changes none');

-- ============ anonymous visitors: nothing; deleting the child removes the plan
select is(has_table_privilege('anon', 'public.child_vaccines', 'SELECT'), false, 'anon cannot read the table at all');
delete from public.care_recipients where id = 'd0000000-0000-0000-0000-0000000945f1';
select is((select count(*)::int from public.child_vaccines), 0, 'the plan goes with the child');

select * from finish();
rollback;
