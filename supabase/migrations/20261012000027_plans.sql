-- Pricing plans with limits and a 30-day free trial. The numbers live in the `plans` table, so they can be changed
-- with one UPDATE and no new release. A circle's limits come from the plan of the person who created it (the owner).
-- Nobody can change their own plan or trial from the app: it is switched by hand (Supabase SQL editor) until payments exist.
--
-- "Unlimited" (NULL) plans are capped by a system ceiling: 20 parents, 50 people in a circle, 100 medicines per parent.
--
-- Free = a small plan. Every account starts with a 30-day trial of Plus: while the trial runs, a Free account gets the
-- limits of Plus. When it ends the account is on Free again (nothing is deleted; new additions are limited).

create table public.plans (
  key            text primary key,
  name           text not null,
  max_parents    integer check (max_parents > 0),            -- parents one caregiver can create (NULL = unlimited)
  max_members    integer check (max_members > 0),            -- people in one family circle, owner included (NULL = unlimited)
  max_medicines  integer check (max_medicines > 0),          -- medicines per parent (NULL = unlimited)
  sort_order     integer not null default 0
);
insert into public.plans (key, name, max_parents, max_members, max_medicines, sort_order) values
  ('free',   'Free',   1,  1,  2, 1),
  ('plus',   'Plus',   1,  5, 10, 2),
  ('family', 'Family', 4, 10, 20, 3),
  ('ultra',  'Ultra',  null, null, null, 4);   -- "unlimited" still has a system ceiling (see the limit functions): a runaway account must never cost us unbounded money

alter table public.plans enable row level security;
create policy plans_read on public.plans for select to authenticated using (true);
revoke all on public.plans from public, anon, authenticated;
grant select on public.plans to authenticated;

alter table public.profiles
  add column plan text not null default 'free' references public.plans (key),
  add column trial_ends_at timestamptz not null default (now() + interval '30 days');   -- existing accounts get 30 days from today

-- A caregiver cannot upgrade themselves or extend the trial. (The service role and the SQL editor are not "authenticated".)
create function public.guard_profile_plan() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated'
     and (new.plan is distinct from old.plan or new.trial_ends_at is distinct from old.trial_ends_at) then
    raise exception 'the plan can only be changed by the system' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger profiles_guard_plan before update on public.profiles
  for each row execute function public.guard_profile_plan();

-- The plan whose limits apply to a person: a Free account on trial gets Plus; a paid plan is always itself.
create function public.plan_of_user(p_user uuid) returns public.plans
language sql stable security definer set search_path = '' as $$
  select pl.* from public.plans pl
  where pl.key = coalesce(
    (select case when p.plan = 'free' and p.trial_ends_at > now() then 'plus' else p.plan end from public.profiles p where p.id = p_user),
    'free')
$$;
revoke execute on function public.plan_of_user(uuid) from public, anon;
grant execute on function public.plan_of_user(uuid) to authenticated, service_role;

-- The limits that apply to a circle: its creator's plan (Free if the creator is gone).
create function public.plan_for_circle(p_circle uuid) returns public.plans
language sql stable security definer set search_path = '' as $$
  select public.plan_of_user((select c.created_by from public.care_circles c where c.id = p_circle))
$$;
revoke execute on function public.plan_for_circle(uuid) from public, anon;
grant execute on function public.plan_for_circle(uuid) to authenticated, service_role;

-- What the app shows: my plan, the limits that apply right now, the trial, and how many parents I have created.
-- would_pause_*: what the Free plan would pause if the trial ended today (0 unless on trial), so the family can be warned early.
create function public.plan_overview() returns jsonb
language sql stable security invoker set search_path = '' as $$
  select jsonb_build_object(
    'plan', p.plan,                                             -- what the account is on (free / plus / family / ultra)
    'effective', e.key, 'name', e.name,                         -- what applies right now (a trial counts as Plus)
    'max_parents', e.max_parents, 'max_members', e.max_members, 'max_medicines', e.max_medicines,
    'on_trial', (p.plan = 'free' and p.trial_ends_at > now()),
    'trial_ends_at', p.trial_ends_at,
    'parents_used', (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id),
    'would_pause_parents', case when p.plan = 'free' and p.trial_ends_at > now() then
        greatest(0, (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id) - (select max_parents from public.plans where key = 'free')) else 0 end,
    'would_pause_medicines', case when p.plan = 'free' and p.trial_ends_at > now() then coalesce((
        select sum(greatest(0, x.meds - (select max_medicines from public.plans where key = 'free')))
        from (select r.id, r.created_at,
                     row_number() over (order by r.created_at, r.id) as parent_rank,
                     (select count(*) from public.medicines m where m.recipient_id = r.id and m.active) as meds
                from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id) x
        where x.parent_rank <= (select max_parents from public.plans where key = 'free')), 0) else 0 end
  )
  from public.profiles p, lateral public.plan_of_user(p.id) e
  where p.id = (select auth.uid())
$$;
revoke execute on function public.plan_overview() from public, anon;
grant execute on function public.plan_overview() to authenticated;

-- ---- parents: at most max_parents per caregiver, whichever way the row arrives (the app's function or a direct insert)
create function public.limit_parents() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid;
  v_max integer;
begin
  select created_by into v_owner from public.care_circles where id = new.circle_id;
  if v_owner is null then return new; end if;
  perform pg_advisory_xact_lock(hashtext('parents:' || v_owner::text));
  select max_parents into v_max from public.plan_of_user(v_owner);
  if (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = v_owner) >= coalesce(v_max, 20) then   -- 20 = system ceiling for "unlimited"
    raise exception 'plan limit: parents' using errcode = 'P0001', hint = 'plan_parents';
  end if;
  return new;
end $$;
create trigger care_recipients_limit before insert on public.care_recipients
  for each row execute function public.limit_parents();

create or replace function public.create_care_recipient(p jsonb) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_circle uuid;
  v_recipient uuid;
begin
  insert into public.care_circles (created_by) values ((select auth.uid()))
  returning id into v_circle;

  insert into public.care_recipients (
    circle_id, name, call_name, relation, age, dob, city, whatsapp_phone, timezone, language,
    conditions, allergies, blood_group, doctor_contacts, emergency_contacts, caregiver_attested_at
  ) values (
    v_circle,
    p ->> 'name',
    nullif(btrim(p ->> 'call_name'), ''),
    coalesce(p ->> 'relation', 'other'),
    nullif(p ->> 'age', '')::smallint,
    nullif(p ->> 'dob', '')::date,
    nullif(p ->> 'city', ''),
    nullif(p ->> 'whatsapp_phone', ''),
    coalesce(nullif(p ->> 'timezone', ''), 'Asia/Kolkata'),
    coalesce(nullif(p ->> 'language', ''), 'en'),
    coalesce(array(select jsonb_array_elements_text(coalesce(p -> 'conditions', '[]'))), '{}'),
    coalesce(array(select jsonb_array_elements_text(coalesce(p -> 'allergies', '[]'))), '{}'),
    nullif(p ->> 'blood_group', ''),
    coalesce(p -> 'doctor_contacts', '[]'),
    coalesce(p -> 'emergency_contacts', '[]'),
    case when (p ->> 'caregiver_attested')::boolean then now() end
  ) returning id into v_recipient;

  return v_recipient;
end $$;

-- ---- medicines: at most max_medicines per parent (the old fixed 30 is now the Family plan)
create or replace function public.limit_medicines() returns trigger
language plpgsql set search_path = '' as $$
declare v_max integer;
begin
  perform pg_advisory_xact_lock(hashtext('medicines:' || new.recipient_id::text));
  select (public.plan_for_circle(r.circle_id)).max_medicines into v_max from public.care_recipients r where r.id = new.recipient_id;
  if (select count(*) from public.medicines where recipient_id = new.recipient_id) >= coalesce(v_max, 100) then   -- 100 = system ceiling for "unlimited"
    raise exception 'too many medicines' using errcode = 'P0001', hint = 'too_many_medicines';
  end if;
  return new;
end $$;

-- ---- family members: the circle size limit now comes from the plan
create or replace function public.create_family_invite(p_circle uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_token text;
begin
  if v_uid is null or not public.is_circle_member(p_circle) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  if not exists (select 1 from public.care_recipients where circle_id = p_circle) then
    raise exception 'no parent in this circle' using errcode = 'P0001', hint = 'no_parent';
  end if;
  if (select count(*) from public.family_invites
       where circle_id = p_circle and revoked_at is null and accepted_at is null and expires_at > now()) >= 10 then
    raise exception 'too many pending invites' using errcode = 'P0001', hint = 'too_many_invites';
  end if;
  if (select count(*) from public.care_circle_members where circle_id = p_circle) >= coalesce((public.plan_for_circle(p_circle)).max_members, 50) then   -- 50 = system ceiling for "unlimited"
    raise exception 'circle is full' using errcode = 'P0001', hint = 'circle_full';
  end if;
  v_token := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  insert into public.family_invites (circle_id, created_by, token_hash)
  values (p_circle, v_uid, public.invite_hash(v_token));
  return v_token;
end $$;

create or replace function public.accept_family_invite(p_token text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_inv public.family_invites;
  v_recipient uuid;
begin
  if v_uid is null then raise exception 'not allowed' using errcode = '42501'; end if;
  if p_token is null or p_token !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid';
  end if;
  select * into v_inv from public.family_invites where token_hash = public.invite_hash(p_token) for update;
  if not found then raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid'; end if;
  if exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_uid) then
    raise exception 'already a member' using errcode = 'P0001', hint = 'member';
  end if;
  if v_inv.accepted_at is not null then raise exception 'invite used' using errcode = 'P0001', hint = 'used'; end if;
  if v_inv.revoked_at is not null then raise exception 'invite revoked' using errcode = 'P0001', hint = 'revoked'; end if;
  if v_inv.expires_at <= now() then raise exception 'invite expired' using errcode = 'P0001', hint = 'expired'; end if;
  if v_inv.created_by is null or not exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_inv.created_by) then
    raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid';   -- the inviter left or was removed
  end if;
  select id into v_recipient from public.care_recipients where circle_id = v_inv.circle_id order by created_at limit 1;
  if v_recipient is null then
    raise exception 'no parent in this circle' using errcode = 'P0001', hint = 'no_parent';
  end if;
  if (select count(*) from public.care_circle_members where circle_id = v_inv.circle_id) >= coalesce((public.plan_for_circle(v_inv.circle_id)).max_members, 50) then   -- 50 = system ceiling for "unlimited"
    raise exception 'circle is full' using errcode = 'P0001', hint = 'circle_full';
  end if;

  insert into public.care_circle_members (circle_id, user_id, role) values (v_inv.circle_id, v_uid, 'member');
  update public.family_invites set accepted_at = now(), accepted_by = v_uid where id = v_inv.id;

  insert into public.notifications (user_id, type, payload)
  select m.user_id, 'member_joined', jsonb_build_object('recipient_id', v_recipient, 'circle_id', v_inv.circle_id, 'member_id', v_uid)
    from public.care_circle_members m
   where m.circle_id = v_inv.circle_id and m.user_id <> v_uid;
  return v_recipient;
end $$;

-- ---- reminders above the plan's limits are PAUSED (nothing is deleted; upgrading resumes them on the next tick)
-- The oldest parents / medicines stay active up to the limit; the newer ones pause. Medicines the family paused themselves do not count.
create function public.paused_recipients() returns table (recipient_id uuid)
language sql stable security definer set search_path = '' as $$
  select x.id from (
    select r.id, c.created_by, row_number() over (partition by c.created_by order by r.created_at, r.id) as rn
      from public.care_recipients r join public.care_circles c on c.id = r.circle_id
  ) x
  where x.created_by is not null
    and x.rn > coalesce((public.plan_of_user(x.created_by)).max_parents, 20)
$$;
create function public.paused_medicines() returns table (medicine_id uuid)
language sql stable security definer set search_path = '' as $$
  select x.id from (
    select m.id, r.circle_id, row_number() over (partition by m.recipient_id order by m.created_at, m.id) as rn
      from public.medicines m join public.care_recipients r on r.id = m.recipient_id
     where m.active
  ) x
  where x.rn > coalesce((public.plan_for_circle(x.circle_id)).max_medicines, 100)
$$;
revoke execute on function public.paused_recipients() from public, anon, authenticated;
revoke execute on function public.paused_medicines() from public, anon, authenticated;
grant execute on function public.paused_recipients() to service_role;
grant execute on function public.paused_medicines() to service_role;

-- What is paused, for the family to see: only parents and medicines of circles the caller belongs to.
create function public.plan_pauses() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'parents', coalesce((select jsonb_agg(jsonb_build_object('id', r.id)) from public.care_recipients r
                          where r.id in (select recipient_id from public.paused_recipients()) and public.is_circle_member(r.circle_id)), '[]'::jsonb),
    'medicines', coalesce((select jsonb_agg(jsonb_build_object('id', m.id, 'name', m.name, 'recipient_id', m.recipient_id)) from public.medicines m
                            join public.care_recipients r on r.id = m.recipient_id
                           where m.id in (select medicine_id from public.paused_medicines()) and public.is_circle_member(r.circle_id)), '[]'::jsonb)
  )
$$;
revoke execute on function public.plan_pauses() from public, anon;
grant execute on function public.plan_pauses() to authenticated;

create or replace function public.generate_dose_events(
  p_now timestamptz default now(),
  p_horizon interval default '24 hours',
  p_grace interval default '60 minutes',
  p_recipients uuid[] default null          -- NULL = everyone (production); otherwise only these parents
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_created integer;
begin
  with zones as (select name from pg_catalog.pg_timezone_names),
  candidates as (
    select
      s.id as schedule_id,
      ((d.day + s.time_of_day) at time zone r.timezone) as scheduled_at,
      s.created_at
    from public.medicine_schedules s
    join public.medicines m on m.id = s.medicine_id and m.active
    join public.care_recipients r on r.id = m.recipient_id
    join zones z on z.name = r.timezone
    cross join lateral generate_series(
      ((p_now at time zone r.timezone)::date - 1)::timestamp,
      ((p_now at time zone r.timezone)::date + 2)::timestamp,
      interval '1 day'
    ) as d(day)
    where (p_recipients is null or r.id = any (p_recipients))
      and not exists (select 1 from public.paused_medicines() pm where pm.medicine_id = m.id)     -- above the plan's limit: paused
      and not exists (select 1 from public.paused_recipients() pr where pr.recipient_id = r.id)
      and d.day::date >= s.start_date
      and (s.end_date is null or d.day::date <= s.end_date)
      and extract(dow from d.day)::smallint = any (s.days_of_week)
  ), inserted as (
    insert into public.dose_events (schedule_id, scheduled_at)
    select schedule_id, scheduled_at
    from candidates
    where scheduled_at >= p_now - p_grace
      and scheduled_at <= p_now + p_horizon
      and scheduled_at >= created_at
    on conflict (schedule_id, scheduled_at) do nothing
    returning 1
  )
  select count(*) into v_created from inserted;

  delete from public.dose_events de
  using public.medicine_schedules s, public.medicines m
  where de.schedule_id = s.id and s.medicine_id = m.id
    and de.status = 'pending'
    and (not m.active
         or exists (select 1 from public.paused_medicines() pm where pm.medicine_id = m.id)
         or exists (select 1 from public.paused_recipients() pr where pr.recipient_id = m.recipient_id))
    and (p_recipients is null or m.recipient_id = any (p_recipients));

  return v_created;
end $$;

revoke execute on function public.generate_dose_events(timestamptz, interval, interval, uuid[]) from public, anon, authenticated;
grant execute on function public.generate_dose_events(timestamptz, interval, interval, uuid[]) to service_role;
