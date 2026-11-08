-- "Report a problem" (extends in-app feedback, migrations 0034 / 0035). A problem report can carry:
--
--   feedback.topic     which common problem was tapped (no_message, reminder, wrong_time, not_updated, login, other)
--   feedback.context   a small, transparent diagnostic object the app shows the caregiver before sending: app build, browser
--                      family, screen, language, WhatsApp agreement (yes/no/stopped), who gets the reminders, last reminder
--                      time + status, polls on/off, the recipient's id. ONLY the keys listed in feedback_context_ok() are
--                      accepted, values are short scalars, at most 2000 characters in all. Never names, phone numbers,
--                      medicine names or conditions.
--   feedback.status    new -> looking -> fixed, set by the owner in the Monitor (the Java backend, admin checked + audited)
--   feedback.fixed_at  when it was marked fixed; the reporter then gets an in-app notification (type feedback_fixed)
--   admin_audit.feedback_id  which feedback an admin status change was about
--
-- Users keep the same rights: insert their own row, read only their own, no update or delete. A new row always starts as
-- status 'new' (a trigger resets whatever the client sent), so a caregiver can never mark their own report fixed.
--
-- Sydney: paste into the SQL Editor AFTER migration 0061 (referral codes). Order relative to deploying does not matter:
-- the backend and the web app keep working before it is applied (the backend retries a feedback insert without topic /
-- context when the columns are missing, and the Monitor reads the new columns through to_jsonb). Safe to run twice.

-- ---------------------------------------------------------------- the context check (a function: a check cannot use a subquery)
create or replace function public.feedback_context_ok(p jsonb) returns boolean
language sql immutable set search_path = '' as $$
  select p is null or (
    jsonb_typeof(p) = 'object'
    and char_length(p::text) <= 2000
    and not exists (
      select 1 from jsonb_each(p) e
       where e.key not in ('app_build', 'browser', 'os', 'screen', 'language', 'agreed', 'reminders_to',
                           'last_reminder_at', 'last_reminder_status', 'polls', 'recipient_id')
          or jsonb_typeof(e.value) not in ('string', 'boolean', 'number', 'null')
          or (jsonb_typeof(e.value) = 'string' and char_length(e.value #>> '{}') > 80)
    )
  );
$$;

-- ---------------------------------------------------------------- the new columns
alter table public.feedback add column if not exists topic    text;
alter table public.feedback add column if not exists context  jsonb;
alter table public.feedback add column if not exists status   text not null default 'new';
alter table public.feedback add column if not exists fixed_at timestamptz;

alter table public.feedback drop constraint if exists feedback_topic_check;
alter table public.feedback add constraint feedback_topic_check
  check (topic is null or topic in ('no_message', 'reminder', 'wrong_time', 'not_updated', 'login', 'other'));
alter table public.feedback drop constraint if exists feedback_context_check;
alter table public.feedback add constraint feedback_context_check check (public.feedback_context_ok(context));
alter table public.feedback drop constraint if exists feedback_status_check;
alter table public.feedback add constraint feedback_status_check check (status in ('new', 'looking', 'fixed'));
alter table public.feedback drop constraint if exists feedback_fixed_at_check;
alter table public.feedback add constraint feedback_fixed_at_check check ((status = 'fixed') = (fixed_at is not null));

create index if not exists feedback_open_problems on public.feedback (created_at desc) where kind = 'problem' and status <> 'fixed';

-- ---------------------------------------------------------------- a new report always starts as 'new'
create or replace function public.feedback_starts_new() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.status := 'new';
  new.fixed_at := null;
  return new;
end $$;
drop trigger if exists feedback_starts_new on public.feedback;
create trigger feedback_starts_new before insert on public.feedback
  for each row execute function public.feedback_starts_new();

-- Users still cannot change feedback (0034 revoked update / delete); said again so a re-run cannot widen it.
revoke update, delete, truncate on public.feedback from anon, authenticated;

-- ---------------------------------------------------------------- which feedback an admin action was about
-- (admin_audit comes from migration 0054; skipped on a database without it)
do $$
begin
  if to_regclass('public.admin_audit') is not null then
    alter table public.admin_audit add column if not exists feedback_id uuid;   -- no foreign key, like admin_id
  end if;
end $$;
