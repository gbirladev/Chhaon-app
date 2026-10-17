-- Feedback pictures (migration 0035). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a9100000-0000-0000-0000-000000000001', 'fi-admin@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b9100000-0000-0000-0000-000000000002', 'fi-one@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c9100000-0000-0000-0000-000000000003', 'fi-two@test.dev',   '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
update public.profiles set is_admin = true where id = 'a9100000-0000-0000-0000-000000000001';

select is((select public from storage.buckets where id = 'feedback'), false, 'the feedback bucket is private');
select is((select file_size_limit from storage.buckets where id = 'feedback'), 5242880::bigint, 'the bucket limit is 5 MB per file');

-- caregiver one
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b9100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'b9100000-0000-0000-0000-000000000002/one.png', 'b9100000-0000-0000-0000-000000000002')$$, 'a caregiver can upload into their own folder');
select lives_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'b9100000-0000-0000-0000-000000000002/two.jpg', 'b9100000-0000-0000-0000-000000000002')$$, 'and a second picture');
select throws_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'c9100000-0000-0000-0000-000000000003/sneaky.png', 'b9100000-0000-0000-0000-000000000002')$$, '42501', null, 'cannot upload into another user''s folder');
select throws_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'loose.png', 'b9100000-0000-0000-0000-000000000002')$$, '42501', null, 'cannot upload outside any user folder');
select throws_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'b9100000-0000-0000-0000-000000000002/sub/deep.png', 'b9100000-0000-0000-0000-000000000002')$$, '42501', null, 'no sub-folders');
select is((select count(*)::int from storage.objects where bucket_id = 'feedback'), 2, 'a caregiver reads back their own files');
update storage.objects set name = 'b9100000-0000-0000-0000-000000000002/renamed.png' where bucket_id = 'feedback';
select is((select count(*)::int from storage.objects where bucket_id = 'feedback' and name like '%/one.png'), 1, 'a caregiver cannot rename a file (deleting is blocked by storage itself, and there is no delete policy)');

-- feedback rows with pictures
select lives_ok($$insert into public.feedback (user_id, kind, message, images) values ('b9100000-0000-0000-0000-000000000002', 'problem', 'See pictures', array['b9100000-0000-0000-0000-000000000002/one.png', 'b9100000-0000-0000-0000-000000000002/two.jpg'])$$, 'a message with pictures from the own folder is saved');
select throws_ok($$insert into public.feedback (user_id, kind, message, images) values ('b9100000-0000-0000-0000-000000000002', 'problem', 'Too many', array['b9100000-0000-0000-0000-000000000002/a.png', 'b9100000-0000-0000-0000-000000000002/b.png', 'b9100000-0000-0000-0000-000000000002/c.png', 'b9100000-0000-0000-0000-000000000002/d.png'])$$, '23514', null, 'more than 3 pictures are refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, images) values ('b9100000-0000-0000-0000-000000000002', 'problem', 'Wrong folder', array['c9100000-0000-0000-0000-000000000003/a.png'])$$, '22023', null, 'a picture path in another user''s folder is refused');
select throws_ok($$insert into public.feedback (user_id, kind, message, images) values ('b9100000-0000-0000-0000-000000000002', 'problem', 'Dots', array['b9100000-0000-0000-0000-000000000002/../x.png'])$$, '22023', null, 'a path with ".." is refused');

-- caregiver two cannot read one's files; the admin can
select set_config('request.jwt.claims', '{"sub":"c9100000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from storage.objects where bucket_id = 'feedback'), 0, 'other caregivers cannot read the files');
select set_config('request.jwt.claims', '{"sub":"a9100000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is((select count(*)::int from storage.objects where bucket_id = 'feedback'), 2, 'the admin can read the files');
select is((select cardinality(images) from public.admin_feedback(1)), 2, 'admin_feedback returns the picture paths');

-- daily limit: 2 files exist, so 28 more are fine and the 31st is refused
select set_config('request.jwt.claims', '{"sub":"b9100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
insert into storage.objects (bucket_id, name, owner_id) select 'feedback', 'b9100000-0000-0000-0000-000000000002/f' || g || '.png', 'b9100000-0000-0000-0000-000000000002' from generate_series(1, 28) g;
select throws_ok($$insert into storage.objects (bucket_id, name, owner_id) values ('feedback', 'b9100000-0000-0000-0000-000000000002/over.png', 'b9100000-0000-0000-0000-000000000002')$$, '42501', null, 'more than 30 pictures a day are refused');

select * from finish();
rollback;
