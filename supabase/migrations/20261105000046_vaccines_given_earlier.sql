-- Child vaccines: "Which ones were already given?" (docs/use-cases.md, child care). A family that sets up a child who is already a few
-- months old ticks the doses that are on the vaccination card; most were given in hospital or at the clinic and the exact dates are often
-- not at hand. Until the family says so, a dose whose date has passed is NOT called overdue (the app asks "was it given?" instead).
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that reads these columns (the vaccine list
-- and the new POST /recipients/{id}/vaccines/given-earlier). Needs 20261105000045_child.sql first. Safe to run twice. Then also run
-- supabase/maintenance/restore_table_permissions.sql (it now lists these columns) or check with table_permissions_check.sql.
--
-- 1. given_date_unknown: the dose was given but the family did not record the date. given_on then holds the date of the plan (the due date
--    the family went by) only as a PLACEHOLDER, so every "is it given?" check (given_on is not null) keeps working unchanged; the app shows
--    "Given (date not recorded)" and never shows that placeholder as the date it was given. The family can add the real date any time
--    (that sets given_date_unknown back to false).
-- 2. not_yet_given_at: when the family said "not given yet" about a dose whose date has passed (in the "already given?" step, or with
--    "Mark as not done yet"). Only then does the app say "Overdue since <date>".
-- No analytics, tracking or ads columns, on purpose.

alter table public.child_vaccines add column if not exists given_date_unknown boolean not null default false;
alter table public.child_vaccines add column if not exists not_yet_given_at timestamptz;
comment on column public.child_vaccines.given_date_unknown is
  'Given, but the family did not record the date: given_on then holds the plan date as a placeholder (shown as "date not recorded")';
comment on column public.child_vaccines.not_yet_given_at is
  'When the family said "not given yet" about this dose after its date had passed (only then is it called overdue)';

alter table public.child_vaccines drop constraint if exists child_vaccines_unknown_date_needs_given;
alter table public.child_vaccines add constraint child_vaccines_unknown_date_needs_given
  check (not given_date_unknown or given_on is not null);

-- Row Level Security is unchanged (the existing update policy: family member + guardian confirmed). The signed-in family may change the
-- two new columns; the backend (service_role) already has every right on the table.
grant update (given_date_unknown, not_yet_given_at) on public.child_vaccines to authenticated;
