-- Love loop, database (migration 0033). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(23);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9100000-0000-0000-0000-000000000001', 'love-owner@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9100000-0000-0000-0000-000000000002', 'love-sibling@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9100000-0000-0000-0000-000000000003', 'love-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set is_admin = true where id = 'c9100000-0000-0000-0000-000000000003';
insert into public.care_circles (id, created_by) values ('c0000000-0000-0000-0000-0000000091f1', 'a9100000-0000-0000-0000-000000000001');
insert into public.care_circle_members (circle_id, user_id, role) values ('c0000000-0000-0000-0000-0000000091f1', 'b9100000-0000-0000-0000-000000000002', 'member');
insert into public.care_recipients (id, circle_id, name, relation) values ('d0000000-0000-0000-0000-0000000091f1', 'c0000000-0000-0000-0000-0000000091f1', 'Papa', 'father');
insert into public.medicines (id, recipient_id, name, dosage) values ('e0000000-0000-0000-0000-0000000091f1', 'd0000000-0000-0000-0000-0000000091f1', 'Med A', '1 tablet');
insert into public.medicine_schedules (id, medicine_id, time_of_day) values ('f0000000-0000-0000-0000-0000000091f1', 'e0000000-0000-0000-0000-0000000091f1', '08:00');
insert into public.dose_events (id, schedule_id, scheduled_at, status, sent_at, responded_at) values
  ('a1000000-0000-0000-0000-0000000091f1', 'f0000000-0000-0000-0000-0000000091f1', now() - interval '2 hours', 'taken', now() - interval '2 hours', now() - interval '1 hour'),
  ('a1000000-0000-0000-0000-0000000091f2', 'f0000000-0000-0000-0000-0000000091f1', now() - interval '5 hours', 'sent', now() - interval '5 hours', null),
  ('a1000000-0000-0000-0000-0000000091f3', 'f0000000-0000-0000-0000-0000000091f1', now() - interval '3 days', 'taken', now() - interval '3 days', now() - interval '3 days'),
  ('a1000000-0000-0000-0000-0000000091f4', 'f0000000-0000-0000-0000-0000000091f1', now() - interval '8 hours', 'taken_late', now() - interval '9 hours', now() - interval '8 hours');
insert into public.checkins (id, recipient_id, date, sent_at, responded_at, status) values
  ('a2000000-0000-0000-0000-0000000091f1', 'd0000000-0000-0000-0000-0000000091f1', current_date, now() - interval '3 hours', now() - interval '2 hours', 'replied');

-- ============ the love loop starts OFF
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b9100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','preset',null,'love_you')$$,
  'P0001', 'love_off', 'no reactions while it is off');
select lives_ok($$select public.set_love_enabled('d0000000-0000-0000-0000-0000000091f1', true)$$, 'a family member turns it on');
select is((select enabled::text || ',' || daily_cap || ',' || quiet_start || ',' || quiet_end || ',' || pilot_group from public.love_settings), 'true,3,21:00:00,07:00:00,love', 'defaults: cap 3, quiet 9 PM to 7 AM');
select isnt((select first_enabled_at from public.love_settings), null, 'the start mark for the pilot is recorded');

-- ============ sending a reaction
select lives_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','emoji',E'❤️',null)$$, 'a heart on a taken dose');
select is((select status || ',' || type || ',' || coalesce(text, '-') from public.reactions), 'queued,emoji,-', 'it starts queued');
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','emoji',E'❤️',null)$$, '23505', null, 'the same person cannot react twice to one moment');
select lives_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f4','preset',null,'thank_you')$$, 'a preset on a late-taken dose');
select lives_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','checkin','a2000000-0000-0000-0000-0000000091f1','custom',null,'  Talk to you   tonight  ')$$, 'a note on an answered check-in');
select is((select text from public.reactions where type = 'custom'), 'Talk to you tonight', 'the note is tidied');
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f2','emoji',E'❤️',null)$$, 'P0001', 'love_source_invalid', 'not on a dose that was not taken');
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f3','emoji',E'❤️',null)$$, 'P0001', 'love_source_invalid', 'not on something older than 48 hours');
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f4','custom',null,repeat('x',151))$$, 'P0001', 'love_text_invalid', 'a note is at most 150 characters');

-- ============ the parent opts out of hearts
reset role;
update public.love_settings set parent_opted_out_at = now() where recipient_id = 'd0000000-0000-0000-0000-0000000091f1';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9100000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','emoji',E'\U0001F64F',null)$$, 'P0001', 'love_parent_opted_out', 'no new reactions after NO HEARTS');

-- ============ other families, the control group
select set_config('request.jwt.claims', '{"sub":"c9100000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.reactions), 0, 'a stranger sees no reactions');
select is((select count(*)::int from public.love_settings), 0, 'or settings');
select is((select count(*)::int from public.parent_messages), 0, 'or parent messages');
select throws_ok($$select public.send_reaction('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','emoji',E'❤️',null)$$, '42501', null, 'and cannot react for someone else''s parent');
select throws_ok($$select public.set_love_enabled('d0000000-0000-0000-0000-0000000091f1', false)$$, '42501', null, 'or change the setting');
select throws_ok($$insert into public.reactions (recipient_id, source_type, source_id, type, emoji) values ('d0000000-0000-0000-0000-0000000091f1','dose','a1000000-0000-0000-0000-0000000091f1','emoji',E'❤️')$$, '42501', null, 'and cannot write a reaction directly');

-- ============ the pilot numbers are for the admin only
select lives_ok($$select public.love_stats(14)$$, 'the admin can read the love stats');
select is((public.love_stats(14) -> 'reactions' ->> 'created')::int, 3, 'it counts the reactions');
reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b9100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select throws_ok($$select public.love_stats(14)$$, '42501', null, 'a normal caregiver cannot');

select * from finish();
rollback;
