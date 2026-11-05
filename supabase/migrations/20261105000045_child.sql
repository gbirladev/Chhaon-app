-- Child care, step 3 of docs/use-cases.md: guardian confirmation and the vaccination plan of a child.
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that uses these tables: the backend's
-- vaccine endpoints and the vaccine reminder read them. Safe to run twice. Then also run supabase/maintenance/restore_table_permissions.sql
-- (it now lists child_vaccines) or check with table_permissions_check.sql.
--
-- Independent of 20261105000043 (care_type, due_date) and 20261105000044 (who gets the reminders); any order works.
--
-- 1. Date of birth: care_recipients ALREADY has a nullable column `dob` (since the first schema, sent by the recipient create/update
--    API and passed through by the profile form). It is reused for children instead of adding a second "date_of_birth" column.
--    Only a loose sanity rule is added here (not before 1900, not in the future; one day of slack for time zones). "A child is under 18"
--    is checked by the backend when the family saves a child's date of birth, not in the database, so adults with a dob are unaffected.
--    NOT VALID: rows that already exist are not checked (none are expected to break the rule).
-- 2. guardian_confirmed_at: the signed-in caregiver ticked "I am the parent or legal guardian of this child" (DPDP Act 2023 s.9 +
--    Rules 2025: verifiable consent of a parent or lawful guardian before processing a child's personal data). No child vaccine rows can
--    be written without it (the write policies below check it, and so does the backend).
-- 3. child_vaccines: one row per dose of the child's plan. The plan is SUGGESTED from the date of birth and the IAP-ACVIP 2023 timetable
--    (api-java/src/main/resources/vaccines/iap-acvip-2023.json, to be reviewed by a paediatrician before release). The family can change
--    every date, mark a dose given (with the date) or not given. Nothing changes without the family.
--    Read by every member of the child's family circle; nobody outside it. No analytics, tracking or ads columns, on purpose.

-- ---------------------------------------------------------------- 1. date of birth sanity
alter table public.care_recipients drop constraint if exists care_recipients_dob_sane;
alter table public.care_recipients add constraint care_recipients_dob_sane
  check (dob is null or (dob >= date '1900-01-01' and dob <= current_date + 1)) not valid;

-- ---------------------------------------------------------------- 2. guardian confirmation
alter table public.care_recipients add column if not exists guardian_confirmed_at timestamptz;
alter table public.care_recipients add column if not exists guardian_confirmed_by uuid references public.profiles (id) on delete set null;
comment on column public.care_recipients.guardian_confirmed_at is
  'When a family member confirmed "I am the parent or legal guardian of this child" (needed before a child''s health data is saved)';
comment on column public.care_recipients.guardian_confirmed_by is 'Who confirmed it (the profile, null if that account was deleted)';

-- ---------------------------------------------------------------- 3. the vaccination plan
create table if not exists public.child_vaccines (
  id                   uuid primary key default gen_random_uuid(),
  recipient_id         uuid not null references public.care_recipients (id) on delete cascade,
  schedule_key         text not null check (schedule_key ~ '^[a-z0-9-]{1,40}$'),   -- e.g. 'mmr-1', one per dose in the schedule file
  vaccine_code         text not null check (char_length(vaccine_code) between 1 and 40),  -- e.g. 'mmr'
  dose_label           text not null check (char_length(dose_label) <= 20),             -- e.g. '1', 'B1' (booster 1), '' for a single dose
  schedule_edition     text not null check (char_length(schedule_edition) <= 60),       -- e.g. 'IAP-ACVIP 2023'
  suggested_due_date   date not null,                     -- date of birth + the age in the schedule (what the app suggested)
  due_date             date not null,                     -- the date the family goes by (= suggested until they change it)
  due_date_by_family   boolean not null default false,    -- true once the family changed the date: the plan never moves it again
  given_on             date,                              -- the family marked it given on this date
  not_given            boolean not null default false,    -- the family marked it "not given" (for example the doctor chose another vaccine)
  notes                text check (char_length(notes) <= 300),
  reminded_due_date    date,                              -- the due date the family was last reminded about (a changed date is reminded again)
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  constraint child_vaccines_one_per_dose unique (recipient_id, schedule_key),
  constraint child_vaccines_given_or_not check (not (not_given and given_on is not null))
);
create index if not exists child_vaccines_upcoming on public.child_vaccines (due_date) where given_on is null and not not_given;

drop trigger if exists child_vaccines_updated_at on public.child_vaccines;
create trigger child_vaccines_updated_at before update on public.child_vaccines
  for each row execute function public.set_updated_at();

comment on table public.child_vaccines is
  'A child''s vaccination plan, suggested from the date of birth (IAP-ACVIP timetable), every date editable by the family. Family circle only.';

-- May this signed-in user write the child's vaccine plan? Member of the child's family AND the guardian confirmation is recorded.
create or replace function public.can_write_child_data(p_recipient uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.can_access_recipient(p_recipient)
     and exists (select 1 from public.care_recipients r where r.id = p_recipient and r.guardian_confirmed_at is not null);
$$;
revoke execute on function public.can_write_child_data(uuid) from public, anon;
grant execute on function public.can_write_child_data(uuid) to authenticated, service_role;

alter table public.child_vaccines enable row level security;
drop policy if exists child_vaccines_select on public.child_vaccines;
create policy child_vaccines_select on public.child_vaccines for select to authenticated
  using (public.can_access_recipient(recipient_id));
drop policy if exists child_vaccines_insert on public.child_vaccines;
create policy child_vaccines_insert on public.child_vaccines for insert to authenticated
  with check (public.can_write_child_data(recipient_id));
drop policy if exists child_vaccines_update on public.child_vaccines;
create policy child_vaccines_update on public.child_vaccines for update to authenticated
  using (public.can_write_child_data(recipient_id)) with check (public.can_write_child_data(recipient_id));
drop policy if exists child_vaccines_delete on public.child_vaccines;
create policy child_vaccines_delete on public.child_vaccines for delete to authenticated
  using (public.can_write_child_data(recipient_id));

-- Permissions (written out in full: the Sydney copy once lost its GRANTs). Nothing for anonymous visitors. A signed-in family member may
-- read; write only the plan and family columns (reminder bookkeeping stays with the backend); the backend (service_role) does everything.
revoke all on table public.child_vaccines from public, anon, authenticated, service_role;
grant select, delete on table public.child_vaccines to authenticated;
grant insert (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date, due_date_by_family,
              given_on, not_given, notes) on public.child_vaccines to authenticated;
grant update (suggested_due_date, due_date, due_date_by_family, given_on, not_given, notes) on public.child_vaccines to authenticated;
grant select, insert, update, delete, references, trigger, truncate on table public.child_vaccines to service_role;
