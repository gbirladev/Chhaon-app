-- Who gets the reminders (migration 20261105000044). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(12);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a6000000-0000-0000-0000-000000000001', 'rr-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b6000000-0000-0000-0000-000000000002', 'rr-sibling@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

insert into public.care_circles (id, created_by) values ('c6000000-0000-0000-0000-0000000000a1', 'a6000000-0000-0000-0000-000000000001');
insert into public.care_circles (id, created_by) values ('c6000000-0000-0000-0000-0000000000a2', 'a6000000-0000-0000-0000-000000000001');
insert into public.care_circle_members (circle_id, user_id, role) values ('c6000000-0000-0000-0000-0000000000a1', 'b6000000-0000-0000-0000-000000000002', 'member');
insert into public.care_recipients (id, circle_id, name, call_name, relation, whatsapp_phone)
  values ('d6000000-0000-0000-0000-0000000000d1', 'c6000000-0000-0000-0000-0000000000a1', 'Ramesh', 'Papa', 'father', '+919800000001');
-- the owner's own profile ("Me") with their own number
insert into public.care_recipients (id, circle_id, name, relation, whatsapp_phone, is_self)
  values ('d6000000-0000-0000-0000-0000000000d2', 'c6000000-0000-0000-0000-0000000000a2', 'Ganesh', 'self', '+919800000009', true);

select is((select reminder_to from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), 'person', 'existing and new profiles default to the person themself');

select throws_ok($$update public.care_recipients set reminder_to = 'neighbour' where id = 'd6000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'only person / caregiver / helper');
select throws_ok($$update public.care_recipients set reminder_to = 'helper', reminder_name = null where id = 'd6000000-0000-0000-0000-0000000000d1'$$, '23514', null, 'a helper needs a name');
select throws_ok($$update public.care_recipients set reminder_to = 'caregiver' where id = 'd6000000-0000-0000-0000-0000000000d2'$$, '23514', null, 'your own profile always gets its own reminders');

update public.care_recipients set consent_at = now() where id = 'd6000000-0000-0000-0000-0000000000d1';
update public.care_recipients set reminder_to = 'helper', reminder_name = 'Sunita', reminder_relation = 'maid' where id = 'd6000000-0000-0000-0000-0000000000d1';
select is((select consent_at from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), null, 'a new receiver on the same number must agree again');

update public.care_recipients set consent_at = now() where id = 'd6000000-0000-0000-0000-0000000000d1';
update public.care_recipients set reminder_name = 'Sunita ji' where id = 'd6000000-0000-0000-0000-0000000000d1';
select isnt((select consent_at from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), null, 'renaming the same helper keeps the agreement');

-- your own number on a parent's profile: still refused for the parent themself, allowed when YOU get the reminders
select throws_ok($$update public.care_recipients set whatsapp_phone = '+919800000009' where id = 'd6000000-0000-0000-0000-0000000000d1'$$, 'P0001', 'self_number_in_use', 'own number for a helper is refused');
select lives_ok($$update public.care_recipients set reminder_to = 'caregiver', reminder_name = 'Ganesh', reminder_relation = null,
  reminder_user_id = 'a6000000-0000-0000-0000-000000000001', whatsapp_phone = '+919800000009' where id = 'd6000000-0000-0000-0000-0000000000d1'$$,
  'own number allowed when the caregiver chooses to get the reminders');

-- the sibling gets them, then deletes their account: the number stops getting them
update public.care_recipients set reminder_user_id = 'b6000000-0000-0000-0000-000000000002', whatsapp_phone = '+919800000002' where id = 'd6000000-0000-0000-0000-0000000000d1';
delete from auth.users where id = 'b6000000-0000-0000-0000-000000000002';
select is((select reminder_to from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), 'person', 'back to the person when the receiving caregiver leaves');
select is((select whatsapp_phone from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), null, 'their number is removed');

-- a family member can set it through RLS like any other field, a stranger cannot
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a6000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select lives_ok($$update public.care_recipients set reminder_to = 'helper', reminder_name = 'Kamla', whatsapp_phone = '+919800000003' where id = 'd6000000-0000-0000-0000-0000000000d1'$$, 'the owner sets a helper');
reset role;
select is((select reminder_name from public.care_recipients where id = 'd6000000-0000-0000-0000-0000000000d1'), 'Kamla', 'saved');

select * from finish();
rollback;
