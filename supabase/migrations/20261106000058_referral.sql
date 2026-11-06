-- Which doctor brought each family (docs/TODO.md, "Growth plan: doctors bring families" -> "Count families per doctor").
-- Counting only: nothing is shown to the doctor and nothing commercial flows to them (Regulation 6.4.1 of the 2002 MCI ethics code).
--
--   profiles.referred_by   the doctor code from the printed QR card (chhaon.site/app/?ref=D001), saved at sign-up
--   profiles.heard_from    the optional "How did you hear about Chhaon?" answer
--
-- Rules:
--   * first value wins: once a column is set, a later update never changes it (trigger below; also for the backend).
--   * a caregiver can set their own values (backend PATCH /api/v1/me), but no signed-in user can READ these two columns
--     through Supabase: a brother in the same family must not see which doctor his sister went to. The backend reads them
--     with its own database login. Done with column privileges, because Row Level Security works on rows, not columns.
--   * admin_referral_stats(): counts per code and per answer for the admin Monitor. Counts only, no names or phones.
--
-- Sydney: paste into the SQL Editor BEFORE deploying the backend that maps these columns (it checks the schema at start-up).
-- Safe to run twice. Needs migration 0055 (profiles.is_canary / care_recipients.is_canary) to be there first.

alter table public.profiles add column if not exists referred_by text;
alter table public.profiles add column if not exists heard_from text;

alter table public.profiles drop constraint if exists profiles_referred_by_format;
alter table public.profiles add constraint profiles_referred_by_format
  check (referred_by is null or referred_by ~ '^[A-Z0-9_-]{1,32}$');
alter table public.profiles drop constraint if exists profiles_heard_from_choice;
alter table public.profiles add constraint profiles_heard_from_choice
  check (heard_from is null or heard_from in ('doctor', 'friend_family', 'whatsapp', 'google', 'other'));

comment on column public.profiles.referred_by is
  'Doctor code from the QR card (?ref=D001), upper case. Set once at sign-up, never overwritten. Not readable by signed-in users.';
comment on column public.profiles.heard_from is
  'Optional sign-up answer: doctor / friend_family / whatsapp / google / other. Set once. Not readable by signed-in users.';

-- ---------------------------------------------------------------- first value wins
create or replace function public.keep_first_referral() returns trigger
language plpgsql set search_path = '' as $$
begin
  if old.referred_by is not null then new.referred_by := old.referred_by; end if;
  if old.heard_from is not null then new.heard_from := old.heard_from; end if;
  return new;
end $$;
revoke execute on function public.keep_first_referral() from public, anon, authenticated;

drop trigger if exists profiles_keep_first_referral on public.profiles;
create trigger profiles_keep_first_referral before update on public.profiles
  for each row execute function public.keep_first_referral();

-- ---------------------------------------------------------------- nobody reads them through Supabase
-- Table-wide SELECT is replaced by SELECT on every other column. Re-running this file re-grants all columns that exist
-- then, so a profiles column added by a LATER migration must be granted there (or this block run again):
--   grant select (<new column>) on public.profiles to authenticated;
-- supabase/tests/referral.test.sql fails when one is forgotten, and supabase/maintenance/restore_table_permissions.sql re-applies this lock.
do $$
declare
  v_cols text;
begin
  select string_agg(quote_ident(column_name), ', ' order by ordinal_position) into v_cols
    from information_schema.columns
   where table_schema = 'public' and table_name = 'profiles' and column_name not in ('referred_by', 'heard_from');
  revoke select on public.profiles from anon, authenticated;
  execute format('grant select (%s) on public.profiles to authenticated', v_cols);
end $$;

-- ---------------------------------------------------------------- the admin's counts
create or replace function public.admin_referral_stats() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;

  return (
    with people as (   -- every real caregiver (the canary's dummy account left out), with whether their family got going
      select p.referred_by, p.heard_from,
             exists (select 1 from public.care_circle_members m join public.care_recipients r on r.circle_id = m.circle_id
                      where m.user_id = p.id and not r.is_canary) as has_person,
             exists (select 1 from public.care_circle_members m join public.care_recipients r on r.circle_id = m.circle_id
                      where m.user_id = p.id and not r.is_canary and r.consent_at is not null and r.opted_out_at is null) as agreed
        from public.profiles p
       where not p.is_canary
    )
    select jsonb_build_object(
      'generated_at', now(),
      'total', (select count(*) from people),
      'with_code', (select count(*) from people where referred_by is not null),
      'by_code', coalesce((
        select jsonb_agg(jsonb_build_object('code', code, 'signups', signups, 'with_person', with_person, 'agreed', agreed)
                         order by signups desc, code)
          from (select referred_by as code, count(*) as signups, count(*) filter (where has_person) as with_person,
                       count(*) filter (where agreed) as agreed
                  from people where referred_by is not null group by referred_by) c), '[]'::jsonb),
      'by_heard_from', coalesce((
        select jsonb_agg(jsonb_build_object('answer', answer, 'signups', signups) order by signups desc, answer)
          from (select coalesce(heard_from, 'not_answered') as answer, count(*) as signups
                  from people group by 1) h), '[]'::jsonb)
    )
  );
end $$;
revoke execute on function public.admin_referral_stats() from public, anon;
grant execute on function public.admin_referral_stats() to authenticated;
