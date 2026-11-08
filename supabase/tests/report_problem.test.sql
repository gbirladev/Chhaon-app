-- "Report a problem" (migration 20261108000062): topic, context, status on feedback. Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

select has_column('public', 'feedback', 'topic', 'feedback has a topic');
select has_column('public', 'feedback', 'context', 'feedback has a diagnostic context');
select has_column('public', 'feedback', 'status', 'feedback has a status');
select has_column('public', 'feedback', 'fixed_at', 'feedback has fixed_at');
select col_default_is('public', 'feedback', 'status', 'new', 'status starts as new');

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9100000-0000-0000-0000-000000000001', 'rp-one@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9100000-0000-0000-0000-000000000002', 'rp-two@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9100000-0000-0000-0000-000000000001","role":"authenticated"}', true);

select lives_ok($$insert into public.feedback (user_id, kind, message, topic, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'No message came',
  'no_message', '{"app_build":"abc1234","browser":"Chrome","os":"Android","screen":"today","language":"hi","agreed":"no","reminders_to":"person","last_reminder_at":"2026-11-08T02:30:00Z","last_reminder_status":"sent","polls":false,"recipient_id":"6f1d2c1e-0000-4000-8000-000000000001"}')$$,
  'a problem report with a topic and every allowed context key is accepted');
select lives_ok($$insert into public.feedback (user_id, kind, message) values ('a9100000-0000-0000-0000-000000000001', 'idea', 'Old clients send no topic')$$,
  'an old client without topic / context still works');
select throws_ok($$insert into public.feedback (user_id, kind, message, topic) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', 'printer_on_fire')$$,
  '23514', null, 'an unknown topic is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', '{"phone":"+919999900001"}')$$,
  '23514', null, 'a context key that is not on the list (a phone number) is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', '{"medicine":"Metformin"}')$$,
  '23514', null, 'a medicine name in the context is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', '{"browser":{"nested":"object"}}')$$,
  '23514', null, 'a nested value in the context is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', jsonb_build_object('browser', repeat('b', 81)))$$,
  '23514', null, 'a context value over 80 characters is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, context) values ('a9100000-0000-0000-0000-000000000001', 'problem', 'x', '["not","an","object"]')$$,
  '23514', null, 'a context that is not an object is refused');

-- a caregiver cannot mark their own report fixed: whatever they send, the row starts as new
insert into public.feedback (user_id, kind, message, topic, status, fixed_at)
values ('a9100000-0000-0000-0000-000000000001', 'problem', 'Sneaky', 'other', 'fixed', now());
select is((select status || '/' || coalesce(fixed_at::text, 'null') from public.feedback where message = 'Sneaky'), 'new/null',
  'a new report always starts as new, whatever the client sends');
select throws_ok($$update public.feedback set status = 'fixed', fixed_at = now()$$, '42501', null, 'a caregiver cannot change the status');

-- the other caregiver still sees nothing of it
select set_config('request.jwt.claims', '{"sub":"b9100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is((select count(*)::int from public.feedback), 0, 'other caregivers cannot read problem reports');

-- the backend (service role) moves a report along; the status and fixed_at rules hold
reset role;
select lives_ok($$update public.feedback set status = 'looking' where message = 'Sneaky'$$, 'the backend can set looking');
select lives_ok($$update public.feedback set status = 'fixed', fixed_at = now() where message = 'Sneaky'$$, 'the backend can set fixed with a time');
select throws_ok($$update public.feedback set status = 'done' where message = 'Sneaky'$$, '23514', null, 'an unknown status is refused');
select throws_ok($$update public.feedback set status = 'looking' where message = 'Sneaky'$$, '23514', null, 'fixed_at is only set while fixed');

select * from finish();
rollback;
