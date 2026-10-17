-- In-app feedback (migration 0034). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9000000-0000-0000-0000-000000000001', 'fb-admin@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9000000-0000-0000-0000-000000000002', 'fb-one@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9000000-0000-0000-0000-000000000003', 'fb-two@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set is_admin = true where id = 'a9000000-0000-0000-0000-000000000001';
update public.profiles set name = 'Asha Sender' where id = 'b9000000-0000-0000-0000-000000000002';

-- caregiver one
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b9000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$insert into public.feedback (user_id, kind, message, rating, screen, app_language) values ('b9000000-0000-0000-0000-000000000002', 'idea', 'Please add dark mode', 4, 'family', 'en')$$, 'a caregiver can send feedback as themselves');
select lives_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'praise', 'Thank you')$$, 'rating and screen are optional');
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('c9000000-0000-0000-0000-000000000003', 'idea', 'Pretending to be someone else')$$, '42501', null, 'cannot send feedback as someone else');
select throws_ok($$insert into public.feedback (kind, message) values ('idea', 'No sender')$$, '42501', null, 'cannot send feedback without your own user id');
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'idea', '')$$, '23514', null, 'an empty message is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'idea', '   ')$$, '23514', null, 'a blank message is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'idea', repeat('x', 1001))$$, '23514', null, 'a message over 1000 characters is refused');
select lives_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'idea', repeat('x', 1000))$$, 'exactly 1000 characters is fine');
select throws_ok($$insert into public.feedback (user_id, kind, message, rating) values ('b9000000-0000-0000-0000-000000000002', 'idea', 'ok', 6)$$, '23514', null, 'a rating above 5 is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'rant', 'ok')$$, '23514', null, 'an unknown kind is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, screen) values ('b9000000-0000-0000-0000-000000000002', 'idea', 'ok', repeat('s', 41))$$, '23514', null, 'a screen name over 40 characters is refused');
select is((select count(*)::int from public.feedback), 3, 'a caregiver reads back their own rows');
select throws_ok($$update public.feedback set message = 'edited'$$, '42501', null, 'a caregiver cannot edit feedback');
select throws_ok($$delete from public.feedback$$, '42501', null, 'a caregiver cannot delete feedback');

-- caregiver two sees nothing of caregiver one, and cannot call the admin function
select set_config('request.jwt.claims', '{"sub":"c9000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.feedback), 0, 'other caregivers cannot read it');
select throws_ok($$select * from public.admin_feedback()$$, '42501', null, 'a normal caregiver cannot call admin_feedback');

-- the admin sees everything, with the sender
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is((select sender_name || ' ' || sender_email from public.admin_feedback(1)), 'Asha Sender fb-one@test.dev', 'the admin sees who wrote it (newest first)');

-- daily limit: 3 already sent above, so 7 more are fine and the 11th is refused
reset role;
select set_config('request.jwt.claims', '{"sub":"b9000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
set local role authenticated;
insert into public.feedback (user_id, kind, message) select 'b9000000-0000-0000-0000-000000000002', 'other', 'filler ' || g from generate_series(1, 7) g;
select throws_ok($$insert into public.feedback (user_id, kind, message) values ('b9000000-0000-0000-0000-000000000002', 'other', 'one too many')$$, '53400', null, 'the 11th message in a day is refused');
select is((select count(*)::int from public.feedback), 10, 'exactly 10 are stored');

select * from finish();
rollback;
