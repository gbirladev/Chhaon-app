-- READ-ONLY check of table permissions: prints one line per table in the public schema.
-- Run it in the SQL editor of the database you want to check and compare with the expected lines below (from the local database built from
-- the migrations, 2026-11-03). Columns: table | number of columns | columns an anonymous visitor may INSERT | columns a signed-in user may
-- INSERT | columns a signed-in user may UPDATE | may a signed-in user DELETE | is row security on.
-- A table whose line differs lost (or gained) permission rules. Regenerate the expected lines after a migration adds or changes a table:
-- run this same query on a database built from the migrations.
--
-- EXPECTED:
--   appointments | 11 | 11 | 6 | 5 | t | t
--   care_circle_members | 5 | 5 | 5 | 0 | f | t
--   care_circles | 3 | 3 | 3 | 3 | t | t
--   care_recipients | 24 | 24 | 24 | 24 | t | t
--   checkins | 6 | 6 | 6 | 6 | t | t
--   child_vaccines | 17 | 0 | 11 | 8 | t | t   (migrations 0045, 0046)
--   consent_requests | 4 | 4 | 4 | 4 | t | t
--   document_files | 8 | 8 | 8 | 8 | t | t
--   documents | 10 | 10 | 10 | 10 | t | t
--   dose_events | 7 | 7 | 7 | 7 | t | t
--   emergency_links | 9 | 0 | 0 | 0 | f | t
--   escalation_policies | 9 | 9 | 9 | 9 | t | t
--   escalations | 12 | 12 | 12 | 12 | t | t
--   family_invites | 9 | 0 | 0 | 0 | f | t
--   feedback | 9 | 0 | 9 | 0 | f | t
--   love_settings | 10 | 10 | 10 | 10 | t | t
--   medicine_schedules | 7 | 7 | 7 | 7 | t | t
--   medicines | 12 | 12 | 12 | 12 | t | t
--   notifications | 9 | 9 | 9 | 1 | t | t
--   parent_messages | 7 | 7 | 7 | 7 | t | t
--   phone_opt_outs | 2 | 2 | 2 | 2 | t | t
--   plans | 7 | 0 | 0 | 0 | f | t
--   profiles | 19 | 19 | 19 | 19 | t | t
--   reactions | 20 | 20 | 20 | 20 | t | t
--   report_values | 8 | 8 | 8 | 8 | t | t
--   reports | 9 | 9 | 9 | 2 | t | t
--   weekly_summary_sends | 8 | 0 | 0 | 0 | f | t   (migration 0048)
--   whatsapp_events | 2 | 2 | 2 | 2 | t | t

select c.relname as tbl,
       (select count(*) from pg_attribute a where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped) as cols,
       (select count(*) from pg_attribute a where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped and has_column_privilege('anon', c.oid, a.attnum, 'INSERT')) as anon_insert_cols,
       (select count(*) from pg_attribute a where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped and has_column_privilege('authenticated', c.oid, a.attnum, 'INSERT')) as signed_in_insert_cols,
       (select count(*) from pg_attribute a where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped and has_column_privilege('authenticated', c.oid, a.attnum, 'UPDATE')) as signed_in_update_cols,
       has_table_privilege('authenticated', c.oid, 'DELETE') as signed_in_delete,
       c.relrowsecurity as row_security_on
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' and c.relkind = 'r'
order by 1;
