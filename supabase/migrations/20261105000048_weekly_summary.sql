-- Weekly summary to the family (feature F3, docs/weekly-summary.md).
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend: the backend's weekly summary timer
-- writes this table (without it the timer only logs a warning and sends nothing; the reminder tick is not affected). Safe to run twice.
-- Then also run supabase/maintenance/restore_table_permissions.sql (it now lists weekly_summary_sends) or check with
-- table_permissions_check.sql.
--
-- Independent of the other 2026-11-05 migrations; any order works.
--
-- weekly_summary_sends: one row per caregiver per summary week, so the Sunday WhatsApp summary goes out AT MOST ONCE per caregiver per
-- week, also across restarts and overlapping runs (the row is claimed before sending). week_of = the caregiver's own local Sunday.
-- Holds no health data (only who, which week, how it went). Deleted with the caregiver's account (on delete cascade).
-- Backend only: no access at all for anonymous visitors or signed-in users (row security on, no policies).

create table if not exists public.weekly_summary_sends (
  user_id     uuid not null references public.profiles (id) on delete cascade,
  week_of     date not null,                                   -- the caregiver's local Sunday the summary belongs to
  status      text not null default 'sending'
              check (status in ('sending', 'sent', 'failed', 'skipped')),
  attempts    smallint not null default 0 check (attempts >= 0), -- failed WhatsApp sends so far (at most 3, then it stays 'failed')
  people      smallint check (people >= 0),                    -- how many people the message covered
  claimed_at  timestamptz,
  sent_at     timestamptz,
  created_at  timestamptz not null default now(),
  primary key (user_id, week_of)
);

comment on table public.weekly_summary_sends is
  'Which weekly WhatsApp summary went to which caregiver (at most one per caregiver per week). Backend only, no health data.';

alter table public.weekly_summary_sends enable row level security;

revoke all on table public.weekly_summary_sends from public, anon, authenticated, service_role;
grant select, insert, update, delete, references, trigger, truncate on table public.weekly_summary_sends to service_role;
