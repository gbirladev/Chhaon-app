-- Reports wallet (migration 0029). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9000000-0000-0000-0000-000000000001', 'doc-owner@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9000000-0000-0000-0000-000000000002', 'doc-sibling@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9000000-0000-0000-0000-000000000003', 'doc-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set plan = 'ultra' where id = 'a9000000-0000-0000-0000-000000000001';   -- two parents below

-- two parents of the owner; the sibling shares the first one
insert into public.care_circles (id, created_by) values
  ('c0000000-0000-0000-0000-0000000000d1', 'a9000000-0000-0000-0000-000000000001'),
  ('c0000000-0000-0000-0000-0000000000d2', 'a9000000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, relation) values
  ('d0000000-0000-0000-0000-0000000000a1', 'c0000000-0000-0000-0000-0000000000d1', 'Papa', 'father'),
  ('d0000000-0000-0000-0000-0000000000a2', 'c0000000-0000-0000-0000-0000000000d2', 'Mummy', 'mother');
insert into public.care_circle_members (circle_id, user_id, role) values ('c0000000-0000-0000-0000-0000000000d1', 'b9000000-0000-0000-0000-000000000002', 'member');

create temp table t_ids (k text primary key, v uuid);
grant all on t_ids to authenticated;

-- ============ the owner stores a report
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into t_ids select 'doc', id from public.documents where false;
select lives_ok($$insert into public.documents (recipient_id, title, category, doc_date, doctor, notes) values ('d0000000-0000-0000-0000-0000000000a1', 'Blood test', 'lab', '2026-10-01', 'Dr. Mehta', 'Fasting')$$, 'the owner can store a report');
select is((select uploaded_by from public.documents where title = 'Blood test'), 'a9000000-0000-0000-0000-000000000001'::uuid, 'the uploader is stamped from the login');
select lives_ok($$insert into public.document_files (document_id, path, thumb_path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/page1.jpg', 'd0000000-0000-0000-0000-0000000000a1/page1-t.jpg', 'image/jpeg', 250000)$$, 'a page can be attached');

-- ============ who can see it
select set_config('request.jwt.claims', '{"sub":"b9000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is((select count(*)::int from public.documents), 1, 'a family member sees the report');
select is((select count(*)::int from public.document_files), 1, 'and its pages');
select set_config('request.jwt.claims', '{"sub":"c9000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.documents), 0, 'a stranger sees nothing');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000a1', 'Sneaky')$$, '42501', null, 'a stranger cannot add to a parent they do not belong to');

-- ============ rules on the data
select set_config('request.jwt.claims', '{"sub":"a9000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000a1', '   ')$$, '23514', null, 'a blank title is refused');
select throws_ok($$insert into public.documents (recipient_id, title, category) values ('d0000000-0000-0000-0000-0000000000a1', 'X', 'secret')$$, '23514', null, 'an unknown category is refused');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000a1', E'Bad\ntitle')$$, '23514', null, 'a title with line breaks is refused');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a2/other.jpg', 'image/jpeg', 100)$$, '22023', null, 'a file in another parent''s folder is refused');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/../x.jpg', 'image/jpeg', 100)$$, '22023', null, 'a path with .. is refused');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/x.exe', 'application/x-msdownload', 100)$$, '23514', null, 'only photos and PDFs are accepted');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/big.pdf', 'application/pdf', 20000000)$$, '23514', null, 'a file over 15 MB is refused');

-- ============ ceilings: 10 pages per document, 200 documents per parent
select lives_ok($$insert into public.document_files (document_id, path, mime, size_bytes) select (select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/p' || i || '.jpg', 'image/jpeg', 1000 from generate_series(2, 10) i$$, 'up to 10 pages');
select throws_ok($$insert into public.document_files (document_id, path, mime, size_bytes) values ((select id from public.documents where title = 'Blood test'), 'd0000000-0000-0000-0000-0000000000a1/p11.jpg', 'image/jpeg', 1000)$$, 'P0001', null, 'the 11th page is refused');
select lives_ok($$insert into public.documents (recipient_id, title) select 'd0000000-0000-0000-0000-0000000000a2', 'Doc ' || i from generate_series(1, 200) i$$, 'up to 200 documents for a parent');
select throws_ok($$insert into public.documents (recipient_id, title) values ('d0000000-0000-0000-0000-0000000000a2', 'One too many')$$, 'P0001', null, 'the 201st is refused');

-- ============ deleting a parent removes their documents and pages with them
reset role;
select set_config('request.jwt.claims', '', true);
delete from public.care_recipients where id = 'd0000000-0000-0000-0000-0000000000a1';
select is((select count(*)::int from public.documents where recipient_id = 'd0000000-0000-0000-0000-0000000000a1') + (select count(*)::int from public.document_files where path like 'd0000000-0000-0000-0000-0000000000a1/%'), 0, 'deleting a parent removes their documents and pages');

select * from finish();
rollback;
