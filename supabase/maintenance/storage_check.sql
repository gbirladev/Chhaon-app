-- READ-ONLY check of the file storage on a database: which buckets exist, how many files each holds, and which access rules exist on the files.
-- WHY: the app keeps medicine photos, reports and feedback pictures in three PRIVATE buckets (migrations 0003 and 0035). A database copy does NOT
-- bring storage over: after the move to Sydney, uploads can fail ("bucket not found" or a row-level-security error) and old photos can be missing.
-- EXPECTED (the number of files differs per database; the buckets and the 7 rules must all be there):
--   bucket feedback (private, limit 5242880) | 0 files
--   bucket medicine-photos (private, limit 5242880) | <your number> files
--   bucket reports (private, limit 15728640) | <your number> files
--   storage policy: feedback_files_insert | INSERT
--   storage policy: feedback_files_select_admin | SELECT
--   storage policy: feedback_files_select_own | SELECT
--   storage policy: health_files_delete | DELETE
--   storage policy: health_files_insert | INSERT
--   storage policy: health_files_select | SELECT
--   storage policy: health_files_update | UPDATE

select 'bucket ' || id || ' (' || case when public then 'PUBLIC' else 'private' end || ', limit ' || coalesce(file_size_limit::text, 'none') || ')' as what,
       (select count(*) from storage.objects o where o.bucket_id = b.id)::text || ' files' as detail
from storage.buckets b
union all
select 'storage policy: ' || policyname, cmd::text from pg_policies where schemaname = 'storage' and tablename = 'objects'
order by 1;
