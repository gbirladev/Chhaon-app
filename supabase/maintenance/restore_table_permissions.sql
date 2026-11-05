-- Restore the TABLE permissions the migrations intend (what each role may do with each table), without touching any data.
--
-- WHY: on 2026-11-03 the Sydney database showed full permissions for everyone on every table (the GRANT / REVOKE statements were lost when it was
-- copied). Row security (RLS) was still on and held in tests, but the migrations also limit what a signed-in user may WRITE, for example:
-- emergency_links (no direct writes, so nobody can choose a guessable link), care_circle_members (no updates or deletes, so a member cannot make
-- themselves owner), notifications (only read_at), appointments (not the reminder columns), reports.
--
-- WHAT IT DOES: for each table below it removes all permissions of PUBLIC, anon, authenticated and service_role, then gives back exactly what the
-- correct database gives (generated from the local database built from the migrations), including column-level grants. A table or column that does not
-- exist in the target database is skipped with a notice. Only permissions change, no rows. Safe to run again.
-- (The newest PostgreSQL versions also have a MAINTAIN privilege; it is left out on purpose so the script runs on older versions too.)
--
-- NOT COVERED: tables that are not in the migrations (user_credentials and flyway_schema_history from the Java backend): lock those separately
-- (see docs/TODO.md). Regenerate this file after a migration adds or changes a table or its permissions.

do $$
declare
  s text;
begin
  foreach s in array array[
    'revoke all on table public.appointments from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.appointments to anon',
    'grant DELETE, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.appointments to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.appointments to service_role',
    'grant INSERT (datetime, doctor_name, location, notes, recipient_id, remind) on public.appointments to authenticated',
    'grant UPDATE (datetime, doctor_name, location, notes, remind) on public.appointments to authenticated',
    'revoke all on table public.care_circle_members from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circle_members to anon',
    'grant INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.care_circle_members to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circle_members to service_role',
    'revoke all on table public.care_circles from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to service_role',
    'revoke all on table public.care_recipients from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to service_role',
    'revoke all on table public.checkins from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to service_role',
    'revoke all on table public.child_vaccines from public, anon, authenticated, service_role',
    'grant DELETE, SELECT on table public.child_vaccines to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.child_vaccines to service_role',
    'grant INSERT (dose_label, due_date, due_date_by_family, given_on, not_given, notes, recipient_id, schedule_edition, schedule_key, suggested_due_date, vaccine_code) on public.child_vaccines to authenticated',
    'grant UPDATE (due_date, due_date_by_family, given_on, not_given, notes, suggested_due_date) on public.child_vaccines to authenticated',
    'grant UPDATE (given_date_unknown, not_yet_given_at) on public.child_vaccines to authenticated',   -- migration 0046
    'revoke all on table public.consent_requests from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to service_role',
    'revoke all on table public.document_files from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to service_role',
    'revoke all on table public.documents from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to service_role',
    'revoke all on table public.dose_events from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to service_role',
    'revoke all on table public.emergency_links from public, anon, authenticated, service_role',
    'grant REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.emergency_links to anon',
    'grant REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.emergency_links to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.emergency_links to service_role',
    'revoke all on table public.escalation_policies from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to service_role',
    'revoke all on table public.escalations from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to service_role',
    'revoke all on table public.family_invites from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.family_invites to service_role',
    'grant SELECT (accepted_at, accepted_by, circle_id, created_at, created_by, expires_at, id, revoked_at) on public.family_invites to authenticated',
    'revoke all on table public.feedback from public, anon, authenticated, service_role',
    'grant INSERT, REFERENCES, SELECT, TRIGGER on table public.feedback to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.feedback to service_role',
    'revoke all on table public.love_settings from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to service_role',
    'revoke all on table public.medicine_schedules from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to service_role',
    'revoke all on table public.medicines from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to service_role',
    'revoke all on table public.notifications from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.notifications to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.notifications to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.notifications to service_role',
    'grant UPDATE (read_at) on public.notifications to authenticated',
    'revoke all on table public.parent_messages from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to service_role',
    'revoke all on table public.phone_opt_outs from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to service_role',
    'revoke all on table public.plans from public, anon, authenticated, service_role',
    'grant SELECT on table public.plans to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.plans to service_role',
    'revoke all on table public.profiles from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to service_role',
    'revoke all on table public.reactions from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to service_role',
    'revoke all on table public.report_values from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to service_role',
    'revoke all on table public.reports from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reports to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.reports to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reports to service_role',
    'grant UPDATE (report_date, type) on public.reports to authenticated',
    'revoke all on table public.whatsapp_events from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to service_role'
  ] loop
    begin
      execute s;
    exception when undefined_table or undefined_column then
      raise notice 'skipped (not in this database): %', s;
    end;
  end loop;
end $$;
