-- Reports wallet limits per plan (migration 0029). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(12);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a5000000-0000-0000-0000-000000000001', 'wl-free@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b5000000-0000-0000-0000-000000000002', 'wl-trial@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c5000000-0000-0000-0000-000000000003', 'wl-ultra@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('d5000000-0000-0000-0000-000000000004', 'wl-plus@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
-- set up by hand, as the owner of the app would
-- New accounts start on Family (migration 0039). These tests are about Free, so say so.
update public.profiles set plan = 'free' where id in ('a5000000-0000-0000-0000-000000000001', 'b5000000-0000-0000-0000-000000000002');
update public.profiles set trial_ends_at = now() - interval '1 day' where id in ('a5000000-0000-0000-0000-000000000001', 'c5000000-0000-0000-0000-000000000003', 'd5000000-0000-0000-0000-000000000004');
update public.profiles set plan = 'ultra' where id = 'c5000000-0000-0000-0000-000000000003';
update public.profiles set plan = 'plus'  where id = 'd5000000-0000-0000-0000-000000000004';

insert into public.care_circles (id, created_by) values
  ('c0000000-0000-0000-0000-0000000000e1', 'a5000000-0000-0000-0000-000000000001'),
  ('c0000000-0000-0000-0000-0000000000e2', 'b5000000-0000-0000-0000-000000000002'),
  ('c0000000-0000-0000-0000-0000000000e3', 'c5000000-0000-0000-0000-000000000003'),
  ('c0000000-0000-0000-0000-0000000000e4', 'd5000000-0000-0000-0000-000000000004');
insert into public.care_recipients (id, circle_id, name, relation) values
  ('d0000000-0000-0000-0000-0000000000b1', 'c0000000-0000-0000-0000-0000000000e1', 'Free parent',  'father'),
  ('d0000000-0000-0000-0000-0000000000b2', 'c0000000-0000-0000-0000-0000000000e2', 'Trial parent', 'father'),
  ('d0000000-0000-0000-0000-0000000000b3', 'c0000000-0000-0000-0000-0000000000e3', 'Ultra parent', 'father'),
  ('d0000000-0000-0000-0000-0000000000b4', 'c0000000-0000-0000-0000-0000000000e4', 'Plus parent',  'father');

-- ============ Free: 5 reports
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select lives_ok($$insert into public.documents (recipient_id, title) select 'd0000000-0000-0000-0000-0000000000b1', 'Free ' || i from generate_series(1, 5) i$$, 'Free allows 5 reports');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000b1', 'Sixth')$$, 'P0001', 'too many documents for this plan', 'the 6th report is refused on Free');
select is(public.wallet_usage('d0000000-0000-0000-0000-0000000000b1'), '{"used": 5, "max": 5}'::jsonb, 'the Reports screen can show "5 of 5"');

-- ============ a new account on trial gets the Plus number (25)
select set_config('request.jwt.claims', '{"sub":"b5000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$insert into public.documents (recipient_id, title) select 'd0000000-0000-0000-0000-0000000000b2', 'Trial ' || i from generate_series(1, 25) i$$, 'the trial allows 25 reports');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000b2', 'Trial 26')$$, 'P0001', 'too many documents for this plan', 'the 26th is refused');

-- ============ a downgrade keeps and shows everything, it only stops new additions
select set_config('request.jwt.claims', '{"sub":"d5000000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select lives_ok($$insert into public.documents (recipient_id, title) select 'd0000000-0000-0000-0000-0000000000b4', 'Plus ' || i from generate_series(1, 8) i$$, 'Plus holds 8 reports');
reset role;
select set_config('request.jwt.claims', '', true);
update public.profiles set plan = 'free' where id = 'd5000000-0000-0000-0000-000000000004';   -- the plan shrinks to Free (5)
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"d5000000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select is((select count(*)::int from public.documents where recipient_id = 'd0000000-0000-0000-0000-0000000000b4'), 8, 'after the downgrade all 8 reports are still there and readable');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000b4', 'One more')$$, 'P0001', 'too many documents for this plan', 'but no new report can be added');

-- ============ Ultra: no plan limit (the system ceiling of 200 is tested in documents.test.sql)
select set_config('request.jwt.claims', '{"sub":"c5000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((public.wallet_usage('d0000000-0000-0000-0000-0000000000b3') ->> 'max'), null, 'Ultra reports no plan limit');

-- ============ the hidden 300 MB cap per parent, on every plan (20 files of 15 MB fit exactly)
insert into public.documents (id, recipient_id, title) values ('e0000000-0000-0000-0000-0000000000f1', 'd0000000-0000-0000-0000-0000000000b3', 'Big 1'), ('e0000000-0000-0000-0000-0000000000f2', 'd0000000-0000-0000-0000-0000000000b3', 'Big 2'), ('e0000000-0000-0000-0000-0000000000f3', 'd0000000-0000-0000-0000-0000000000b3', 'Empty');
select lives_ok($$insert into public.document_files (document_id, path, mime, size_bytes) select ('e0000000-0000-0000-0000-0000000000f' || ((i - 1) / 10 + 1))::uuid, 'd0000000-0000-0000-0000-0000000000b3/big' || i || '.pdf', 'application/pdf', 15728640 from generate_series(1, 20) i$$, '20 files of 15 MB (300 MB) fit');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ('e0000000-0000-0000-0000-0000000000f3', 'd0000000-0000-0000-0000-0000000000b3/one-more.pdf', 'application/pdf', 1000)$$, 'P0001', 'storage is full', 'one more byte over 300 MB is refused');

-- ============ someone outside the circle cannot see usage
select set_config('request.jwt.claims', '{"sub":"a5000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$select public.wallet_usage('d0000000-0000-0000-0000-0000000000b3')$$, '42501', null, 'a stranger cannot read another family''s usage');

select * from finish();
rollback;
