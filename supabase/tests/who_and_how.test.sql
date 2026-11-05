-- Relations, the daily check-in switch and the receiver's language (migration 20261105000050). Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a7000000-0000-0000-0000-000000000001', 'wh-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.care_circles (id, created_by) values ('c7000000-0000-0000-0000-0000000000a1', 'a7000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation, whatsapp_phone)
  values ('d7000000-0000-0000-0000-0000000000d1', 'c7000000-0000-0000-0000-0000000000a1', 'Ramesh', 'Papa', 'father', '+919000000071');

select is((select checkin_enabled from public.care_recipients where id = 'd7000000-0000-0000-0000-0000000000d1'), true, 'the check-in is on by default');
select is((select reminder_language from public.care_recipients where id = 'd7000000-0000-0000-0000-0000000000d1'), null, 'no receiver language by default (= the person''s)');

select lives_ok($$update public.care_recipients set relation = 'son' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, 'son is a relation');
select lives_ok($$update public.care_recipients set relation = 'granddaughter' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, 'granddaughter is a relation');
select lives_ok($$update public.care_recipients set relation = 'wife' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, 'wife is a relation');
select throws_ok($$update public.care_recipients set relation = 'neighbour' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'unknown relations are refused');

select lives_ok($$update public.care_recipients set checkin_enabled = false, reminder_language = 'hi' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, 'switch off, Hindi for the receiver');
select throws_ok($$update public.care_recipients set reminder_language = 'ta' where id = 'd7000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'only en / hi');
select throws_ok($$update public.care_recipients set checkin_enabled = null where id = 'd7000000-0000-0000-0000-0000000000d1'$$, '23502', null, 'never null');

select * from finish();
rollback;
