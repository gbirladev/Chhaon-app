-- Referral codes for everyone (docs/referral-codes.md): any caregiver can share Chhaon with their own code, for example
-- chhaon.site/?ref=ANANYA27, and see how many families joined with it. Creators get the same screen.
--
--   referral_codes   one row per code. owner = the caregiver it belongs to; is_primary = the code shown to them now.
--                    Changing the code keeps the old row (is_primary = false) so links already shared still count.
--                    At most 3 changes per person (4 rows): the backend enforces it.
--
-- Rules (the backend checks them; the database backs up the format):
--   * 4-15 characters, A-Z and 0-9, stored upper case.
--   * never the campaign pattern (doctor codes D001 / IND001 / DEL042, creator codes CRE022): those stay free-form in
--     profiles.referred_by without a row here, exactly as before (migration 0058), so the two kinds can never collide.
--   * reserved words (CHHAON, ADMIN, FREE, DOCTOR, ...) are refused by the backend.
-- profiles.referred_by (0058) still stores the code a family joined with; this table only says who owns a code.
-- Nobody reads this table through Supabase: Row Level Security on, no grants to anon / authenticated. The backend uses
-- its own database login. admin_referral_stats() now also names the owner of a user code (admins only).
--
-- Sydney: paste into the SQL Editor AFTER migration 0060 (pilot cap) and BEFORE deploying the backend that serves the
-- "Invite friends" screen. Without it the backend behaves exactly as before (it checks the table exists). Safe to run twice.

-- ---------------------------------------------------------------- the codes
create table if not exists public.referral_codes (
  code       text primary key,
  owner      uuid not null references public.profiles (id) on delete cascade,
  is_primary boolean not null default true,
  created_at timestamptz not null default now()
);
alter table public.referral_codes drop constraint if exists referral_codes_format;
alter table public.referral_codes add constraint referral_codes_format
  check (code ~ '^[A-Z0-9]{4,15}$' and code !~ '^[A-Z]{1,4}[0-9]{3,4}$');
create unique index if not exists referral_codes_one_primary on public.referral_codes (owner) where is_primary;
create index if not exists referral_codes_owner_idx on public.referral_codes (owner);
alter table public.referral_codes enable row level security;
revoke all on table public.referral_codes from anon, authenticated;
comment on table public.referral_codes is
  'Referral codes chosen by caregivers (docs/referral-codes.md). Old codes stay (is_primary = false) so shared links keep counting.';

-- ---------------------------------------------------------------- the admin's counts, now with the owner of a user code
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
        select jsonb_agg(jsonb_strip_nulls(jsonb_build_object('code', code, 'signups', signups, 'with_person', with_person, 'agreed', agreed,
                                            'owner', (select o.name from public.referral_codes rc join public.profiles o on o.id = rc.owner
                                                       where rc.code = c.code)))   -- owner only for a user code
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
