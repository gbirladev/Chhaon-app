-- Weekly summary bookkeeping (migration 20261105000048). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a8000000-0000-0000-0000-000000000001', 'ws-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

select has_table('public', 'weekly_summary_sends', 'the weekly summary table exists');
select is((select relrowsecurity from pg_class where oid = 'public.weekly_summary_sends'::regclass), true, 'row security is on');

insert into public.weekly_summary_sends (user_id, week_of, status) values ('a8000000-0000-0000-0000-000000000001', '2026-11-08', 'sent');
select throws_ok($$insert into public.weekly_summary_sends (user_id, week_of) values ('a8000000-0000-0000-0000-000000000001', '2026-11-08')$$,
  '23505', null, 'at most one summary per caregiver per week');
select throws_ok($$update public.weekly_summary_sends set status = 'maybe' where user_id = 'a8000000-0000-0000-0000-000000000001'$$,
  '23514', null, 'only known statuses');

-- a signed-in user (even the caregiver themself) cannot read or write it: backend only
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a8000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select * from public.weekly_summary_sends$$, '42501', null, 'signed-in users cannot read it');
select throws_ok($$insert into public.weekly_summary_sends (user_id, week_of) values ('a8000000-0000-0000-0000-000000000001', '2026-11-15')$$,
  '42501', null, 'signed-in users cannot write it');
reset role;

delete from public.profiles where id = 'a8000000-0000-0000-0000-000000000001';
select is((select count(*)::int from public.weekly_summary_sends where user_id = 'a8000000-0000-0000-0000-000000000001'), 0,
  'deleting the account deletes its rows');

select * from finish();
rollback;
