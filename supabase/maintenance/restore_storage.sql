-- Restore the file storage buckets and their access rules (what migrations 0003 and 0035 create). Safe to run again. NO FILES ARE TOUCHED:
-- this only creates a missing bucket (private, with its size limit and allowed file types) and (re)creates the access rules.
--
-- WHY: after the move to Sydney, uploading a report or a medicine photo failed and older photos were missing. A database copy does not bring storage
-- over. Run supabase/maintenance/storage_check.sql first; run this if a bucket or a rule is missing.
-- IT DOES NOT BRING BACK THE OLD FILES. Those are in the old (Mumbai) project's storage: copy them across while that project still exists (see docs/TODO.md).
--
-- The rules depend on four functions that must exist: can_access_recipient, storage_recipient_id, feedback_files_today, is_admin_user.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('medicine-photos', 'medicine-photos', false, 5242880, array['image/jpeg', 'image/png', 'image/webp']),
  ('reports',         'reports',         false, 15728640, array['image/jpeg', 'image/png', 'image/webp', 'application/pdf']),
  ('feedback',        'feedback',        false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
  set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

-- Medicine photos and reports: files live at <parent id>/<file name>; access is by family membership.
drop policy if exists health_files_select on storage.objects;
create policy health_files_select on storage.objects for select to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
drop policy if exists health_files_insert on storage.objects;
create policy health_files_insert on storage.objects for insert to authenticated
with check (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
drop policy if exists health_files_update on storage.objects;
create policy health_files_update on storage.objects for update to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
drop policy if exists health_files_delete on storage.objects;
create policy health_files_delete on storage.objects for delete to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);

-- Feedback pictures: into your own folder only, at most 30 a day; read your own; the owner (admin) reads all; nobody can change or delete.
drop policy if exists feedback_files_insert on storage.objects;
create policy feedback_files_insert on storage.objects for insert to authenticated
with check (
  bucket_id = 'feedback'
  and name ~ ('^' || (select auth.uid())::text || '/[A-Za-z0-9._-]{1,120}$')
  and name !~ '\.\.'
  and public.feedback_files_today() < 30
);
drop policy if exists feedback_files_select_own on storage.objects;
create policy feedback_files_select_own on storage.objects for select to authenticated
using (bucket_id = 'feedback' and name like (select auth.uid())::text || '/%');
drop policy if exists feedback_files_select_admin on storage.objects;
create policy feedback_files_select_admin on storage.objects for select to authenticated
using (bucket_id = 'feedback' and public.is_admin_user());
