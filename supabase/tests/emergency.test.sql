-- Run with: npx supabase test db  (written, not yet run)
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email, instance_id, aud, role) values
  ('aaaaaaaa-0000-0000-0000-0000000000e1', 'em-alice@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('bbbbbbbb-0000-0000-0000-0000000000e2', 'em-bob@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-0000000000e1","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('cccccccc-0000-0000-0000-0000000000e1', 'aaaaaaaa-0000-0000-0000-0000000000e1');
insert into public.care_recipients (id, circle_id, name) values ('dddddddd-0000-0000-0000-0000000000e1', 'cccccccc-0000-0000-0000-0000000000e1', 'Papa');

create temp table t_tok (k text, v text);
grant all on t_tok to authenticated;
insert into t_tok select 'first', token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000e1');
select is((select count(*)::int from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000e1')), 1, 'enable is idempotent');
select is((select token from public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000e1')), (select v from t_tok where k = 'first'), 'enable reuses the active token');
select throws_ok($$insert into public.emergency_links (recipient_id, token) values ('dddddddd-0000-0000-0000-0000000000e1', 'abc')$$, '42501', null, 'caregiver cannot insert a link directly (no chosen tokens)');

select public.rotate_emergency_link('dddddddd-0000-0000-0000-0000000000e1');
select isnt((select token from public.emergency_links where recipient_id = 'dddddddd-0000-0000-0000-0000000000e1' and active), (select v from t_tok where k = 'first'), 'rotate makes a new token');
select is((select count(*)::int from public.emergency_links where recipient_id = 'dddddddd-0000-0000-0000-0000000000e1' and active), 1, 'only one active link after rotate');

select public.revoke_emergency_link('dddddddd-0000-0000-0000-0000000000e1');
select is((select count(*)::int from public.emergency_links where recipient_id = 'dddddddd-0000-0000-0000-0000000000e1' and active), 0, 'revoke turns the link off');

select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-0000-0000-0000000000e2","role":"authenticated"}', true);
select throws_ok($$select public.enable_emergency_link('dddddddd-0000-0000-0000-0000000000e1')$$, '42501', null, 'a stranger cannot create a link for someone else''s parent');
select is((select count(*)::int from public.emergency_links), 0, 'a stranger cannot see links');

select * from finish();
rollback;
