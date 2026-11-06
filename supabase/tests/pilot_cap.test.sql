-- Pilot cap (migration 20261106000056, docs/pilot-cap.md). Run with: npx supabase test db  (not alongside pytest)
-- Works on a database that already holds families: the line is emptied inside this transaction and the limits are set relative
-- to the seats already in use, so only the families made here decide the answers. Fake numbers only (+91 99999 ...).
begin;
create extension if not exists pgtap with schema extensions;
select plan(26);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a7000000-0000-0000-0000-000000000001', 'pc-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.profiles (id, name, email) values ('a7000000-0000-0000-0000-000000000001', 'Asha', 'pc-owner@test.dev')
  on conflict (id) do nothing;
update public.profiles set plan = 'ultra' where id = 'a7000000-0000-0000-0000-000000000001';   -- room for seven people
insert into public.care_circles (id, created_by) values ('c7000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation, whatsapp_phone) values
  ('d7000000-0000-0000-0000-000000000001', 'c7000000-0000-0000-0000-0000000000a1', 'One',   'Papa',  'father', '+919999900101'),
  ('d7000000-0000-0000-0000-000000000002', 'c7000000-0000-0000-0000-0000000000a1', 'Two',   'Mummy', 'mother', '+919999900102'),
  ('d7000000-0000-0000-0000-000000000003', 'c7000000-0000-0000-0000-0000000000a1', 'Three', null,    'uncle',  '+919999900103'),
  ('d7000000-0000-0000-0000-000000000004', 'c7000000-0000-0000-0000-0000000000a1', 'Four',  null,    'aunt',   '+919999900104'),
  ('d7000000-0000-0000-0000-000000000005', 'c7000000-0000-0000-0000-0000000000a1', 'Five',  null,    'grandmother', '+919999900102'),
  ('d7000000-0000-0000-0000-000000000006', 'c7000000-0000-0000-0000-0000000000a1', 'Six',   null,    'grandfather', '+919999900106'),
  ('d7000000-0000-0000-0000-000000000007', 'c7000000-0000-0000-0000-0000000000a1', 'Canary', null,   'other',  '+919999900107');
update public.care_recipients set is_canary = true where id = 'd7000000-0000-0000-0000-000000000007';

-- a clean line, and limits relative to what this database already uses
delete from public.pilot_queue;
create temp table base as select public.pilot_seats_used() as seats;
update public.pilot_settings set enabled = true, max_active = (select seats from base) + 2, max_new_per_day = 10;

-- visitors and signed-in users cannot touch any of it
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a7000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.pilot_admit('d7000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000001')$$, '42501', null, 'a signed-in user cannot let anyone in');
select throws_ok($$select * from public.pilot_queue$$, '42501', null, 'a signed-in user cannot read the line');
select throws_ok($$select public.pilot_summary()$$, '42501', null, 'a signed-in user cannot read the summary');
reset role;
select set_config('request.jwt.claims', '', true);
set local role anon;
select throws_ok($$select public.pilot_move_up('d7000000-0000-0000-0000-000000000001')$$, '42501', null, 'a visitor cannot move anyone up');
reset role;

-- two seats free: the first two are let in, the next ones wait in order
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'first person: let in');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000002', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'second person: let in');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000003', 'a7000000-0000-0000-0000-000000000001'),
  '{"status":"pilot_full","position":1}'::jsonb, 'third person: the pilot is full, first in line');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000004', 'a7000000-0000-0000-0000-000000000001'),
  '{"status":"pilot_full","position":2}'::jsonb, 'fourth person: second in line');
select is(public.pilot_position('d7000000-0000-0000-0000-000000000003'), 1, 'pilot_position shows the place in line');
select is(public.pilot_position('d7000000-0000-0000-0000-000000000001'), null, 'someone let in is not in line');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok',
  'asking again after being let in keeps the place, even while full');

-- two parents on one phone: the number already gets our messages, so no new seat and no waiting
update public.care_recipients set consent_at = now() where id = 'd7000000-0000-0000-0000-000000000002';
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000005', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'a second parent on an agreed number is let in at once');
select is((select count(*)::int from public.pilot_queue where recipient_id = 'd7000000-0000-0000-0000-000000000005'), 0, 'and takes no place in the line');
update public.care_recipients set consent_at = now() where id = 'd7000000-0000-0000-0000-000000000005';
select is(public.pilot_seats_used() - (select seats from base), 2, 'one shared phone is one seat');

-- STOP frees a seat: the first in line gets it, the next one moves up
update public.care_recipients set opted_out_at = now() where id = 'd7000000-0000-0000-0000-000000000001';
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000004', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'pilot_full',
  'a free seat goes to the first in line, not to whoever asks');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000003', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'the first in line gets the free seat');
select is(public.pilot_position('d7000000-0000-0000-0000-000000000004'), 1, 'the next one is now first');

-- the owner moves someone to the front
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000006', 'a7000000-0000-0000-0000-000000000001') ->> 'position', '2', 'a new person joins at the back');
select is(public.pilot_move_up('d7000000-0000-0000-0000-000000000006'), true, 'move to front');
select is(public.pilot_position('d7000000-0000-0000-0000-000000000006'), 1, 'now first');
select is(public.pilot_position('d7000000-0000-0000-0000-000000000004'), 2, 'the other one is second');

-- the daily limit: plenty of seats, but only so many new numbers per day
update public.pilot_settings set max_active = 1000, max_new_per_day = (select count(*) from public.pilot_queue where admitted_at > now() - interval '24 hours');
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000006', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'pilot_full', 'today''s new places are used up');
update public.pilot_settings set max_new_per_day = max_new_per_day + 1;
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000006', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'one more place today lets the first in line in');

-- the canary is never limited and never counted
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000007', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'the canary is never limited');

-- the Monitor's summary
select is((public.pilot_summary() -> 'waiting' -> 0 ->> 'name'), 'Four', 'the summary lists who waits, by name (no phone numbers)');

-- switched off: nobody waits
update public.pilot_settings set enabled = false;
select is(public.pilot_admit('d7000000-0000-0000-0000-000000000004', 'a7000000-0000-0000-0000-000000000001') ->> 'status', 'ok', 'with the cap off everyone is let in');

select * from finish();
rollback;
