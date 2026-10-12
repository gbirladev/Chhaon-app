-- Family invites (step 9). Run with: npx supabase test db  (the lead runs it; do not run alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a1000000-0000-0000-0000-000000000001', 'inv-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b1000000-0000-0000-0000-000000000002', 'inv-sib@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c1000000-0000-0000-0000-000000000003', 'inv-other@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

create temp table t_tok (k text primary key, v text);
grant all on t_tok to authenticated;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a1000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('c0000000-0000-0000-0000-0000000000a1', 'a1000000-0000-0000-0000-000000000001');
insert into public.care_recipients (circle_id, name, relation) values ('c0000000-0000-0000-0000-0000000000a1', 'Rajesh Sharma', 'father');
insert into t_tok select 'one', public.create_family_invite('c0000000-0000-0000-0000-0000000000a1');
select ok((select v ~ '^[0-9a-f]{64}$' from t_tok where k = 'one'), 'invite token is 64 hex chars');
select throws_ok($$select token_hash from public.family_invites$$, '42501', null, 'token hash is not readable');
select throws_ok($$insert into public.family_invites (circle_id, token_hash) values ('c0000000-0000-0000-0000-0000000000a1', 'x')$$, '42501', null, 'no direct insert');

-- a stranger
select set_config('request.jwt.claims', '{"sub":"c1000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select throws_ok($$select public.create_family_invite('c0000000-0000-0000-0000-0000000000a1')$$, '42501', null, 'non-member cannot create');
select is((select count(*)::int from public.family_invites), 0, 'stranger cannot list invites');
select is((select public.preview_family_invite('nonsense') ->> 'status'), 'invalid', 'garbage token is invalid');
select is((select public.preview_family_invite((select v from t_tok where k = 'one')) ->> 'parent_first_name'), 'Rajesh', 'preview shows first name only');
select is((select public.preview_family_invite((select v from t_tok where k = 'one')) ->> 'name'), null, 'preview has no full name');

-- the sibling accepts
select set_config('request.jwt.claims', '{"sub":"b1000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$select public.accept_family_invite((select v from t_tok where k = 'one'))$$, 'sibling accepts');
select is((select count(*)::int from public.care_recipients), 1, 'sibling now sees the parent');
select is((select role::text from public.care_circle_members where user_id = 'b1000000-0000-0000-0000-000000000002'), 'member', 'joins as member');
select throws_ok($$select public.accept_family_invite((select v from t_tok where k = 'one'))$$, 'P0001', null, 'a used invite cannot be reused');
select throws_ok($$update public.care_circle_members set role = 'owner' where user_id = 'b1000000-0000-0000-0000-000000000002'$$, '42501', null, 'cannot promote self');

-- owner got a notification, ids only
select set_config('request.jwt.claims', '{"sub":"a1000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is((select count(*)::int from public.notifications where type = 'member_joined'), 1, 'owner is notified');
select throws_ok($$select public.leave_circle('c0000000-0000-0000-0000-0000000000a1')$$, 'P0001', null, 'owner cannot leave');
select throws_ok($$select public.remove_circle_member('c0000000-0000-0000-0000-0000000000a1', 'a1000000-0000-0000-0000-000000000001')$$, 'P0001', null, 'owner cannot remove self');
select lives_ok($$select public.remove_circle_member('c0000000-0000-0000-0000-0000000000a1', 'b1000000-0000-0000-0000-000000000002')$$, 'owner removes member');
select is((select count(*)::int from public.care_circle_members where circle_id = 'c0000000-0000-0000-0000-0000000000a1'), 1, 'only the owner remains');

select * from finish();
rollback;
