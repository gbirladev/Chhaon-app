-- Privacy migration (0019). Run with: npx supabase test db   (not run by the author of this file)
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a1111111-0000-0000-0000-000000000001', 'p-owner@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b2222222-0000-0000-0000-000000000002', 'p-first@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c3333333-0000-0000-0000-000000000003', 'p-second@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('d4444444-0000-0000-0000-000000000004', 'p-solo@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

-- shared circle: owner + two members (first joined earlier); solo circle: one person
insert into public.care_circles (id, created_by) values
  ('c1111111-0000-0000-0000-00000000000a', 'a1111111-0000-0000-0000-000000000001'),
  ('c1111111-0000-0000-0000-00000000000b', 'd4444444-0000-0000-0000-000000000004');
insert into public.care_circle_members (circle_id, user_id, role, created_at) values
  ('c1111111-0000-0000-0000-00000000000a', 'b2222222-0000-0000-0000-000000000002', 'member', now() - interval '2 days'),
  ('c1111111-0000-0000-0000-00000000000a', 'c3333333-0000-0000-0000-000000000003', 'member', now() - interval '1 day');
insert into public.care_recipients (id, circle_id, name) values
  ('e1111111-0000-0000-0000-00000000000a', 'c1111111-0000-0000-0000-00000000000a', 'Shared Papa'),
  ('e1111111-0000-0000-0000-00000000000b', 'c1111111-0000-0000-0000-00000000000b', 'Solo Papa');

select is((select count(*)::int from public.sole_member_recipients('d4444444-0000-0000-0000-000000000004')), 1, 'solo caregiver has one parent that would vanish');
select is((select count(*)::int from public.sole_member_recipients('a1111111-0000-0000-0000-000000000001')), 0, 'shared circle parent would NOT vanish');

select public.erase_caregiver('a1111111-0000-0000-0000-000000000001');
select is((select role::text from public.care_circle_members where circle_id = 'c1111111-0000-0000-0000-00000000000a' and user_id = 'b2222222-0000-0000-0000-000000000002'), 'owner', 'longest-standing member is promoted');
select is((select role::text from public.care_circle_members where circle_id = 'c1111111-0000-0000-0000-00000000000a' and user_id = 'c3333333-0000-0000-0000-000000000003'), 'member', 'the other member stays a member');
select is((select count(*)::int from public.care_circle_members where user_id = 'a1111111-0000-0000-0000-000000000001'), 0, 'leaver is out of the circle');
select is((select count(*)::int from public.care_recipients where id = 'e1111111-0000-0000-0000-00000000000a'), 1, 'shared parent survives');

select public.erase_caregiver('d4444444-0000-0000-0000-000000000004');
select is((select count(*)::int from public.care_circles where id = 'c1111111-0000-0000-0000-00000000000b'), 0, 'sole-member circle is deleted');
select is((select count(*)::int from public.care_recipients where id = 'e1111111-0000-0000-0000-00000000000b'), 0, 'its parent is deleted too');

set local role authenticated;
select throws_ok($$select public.erase_caregiver('b2222222-0000-0000-0000-000000000002')$$, '42501', null, 'a signed-in user cannot call erase_caregiver');

select * from finish();
rollback;
