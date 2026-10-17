-- "Me": a caregiver can add THEMSELVES as a profile and get medicine reminders on their own WhatsApp (docs/self-profile.md).
-- It is a normal profile in its own private circle (so brothers and sisters never see it), it does not count toward the plan's number of
-- parents, and it has no daily check-in, no escalation, no love messages and no missed-dose alerts (the backend skips those for it).

alter table public.care_recipients add column is_self boolean not null default false;

alter table public.care_recipients drop constraint if exists care_recipients_relation_check;
alter table public.care_recipients
  add constraint care_recipients_relation_check
  check (relation in ('father', 'mother', 'grandfather', 'grandmother', 'father_in_law', 'mother_in_law',
                      'spouse', 'uncle', 'aunt', 'brother', 'sister', 'other', 'self'));

-- One own profile per person, always alone in its circle, and it can never be turned into (or from) a parent profile.
create function public.guard_self_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_owner uuid;
begin
  if tg_op = 'UPDATE' then
    if new.is_self is distinct from old.is_self or (new.is_self and new.circle_id is distinct from old.circle_id) then
      raise exception 'an own profile cannot be changed into a parent profile' using errcode = '42501';
    end if;
    return new;
  end if;
  select created_by into v_owner from public.care_circles where id = new.circle_id;
  if not new.is_self then
    if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id and r.is_self) then
      raise exception 'self_circle_private' using errcode = 'P0001';
    end if;
    return new;
  end if;
  if exists (select 1 from public.care_recipients r join public.care_circles c on c.id = r.circle_id where r.is_self and c.created_by = v_owner) then
    raise exception 'self_exists' using errcode = 'P0001';
  end if;
  if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id)
     or exists (select 1 from public.care_circle_members m where m.circle_id = new.circle_id and m.user_id is distinct from v_owner) then
    raise exception 'self_circle_private' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger care_recipients_guard_self before insert or update on public.care_recipients
  for each row execute function public.guard_self_profile();

-- Nobody else can join the circle of an own profile (an invite, a direct insert): it is private to its owner.
create function public.guard_self_circle_members() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id and r.is_self)
     and new.user_id is distinct from (select c.created_by from public.care_circles c where c.id = new.circle_id) then
    raise exception 'self_circle_private' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger care_circle_members_guard_self before insert on public.care_circle_members
  for each row execute function public.guard_self_circle_members();

-- The app's function takes is_self (relation becomes 'self').
create or replace function public.create_care_recipient(p jsonb) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_circle uuid;
  v_recipient uuid;
  v_self boolean := coalesce((p ->> 'is_self')::boolean, false);
begin
  insert into public.care_circles (created_by) values ((select auth.uid()))
  returning id into v_circle;

  insert into public.care_recipients (
    circle_id, name, call_name, relation, age, dob, city, whatsapp_phone, timezone, language,
    conditions, allergies, blood_group, doctor_contacts, emergency_contacts, caregiver_attested_at, is_self
  ) values (
    v_circle,
    p ->> 'name',
    nullif(btrim(p ->> 'call_name'), ''),
    case when v_self then 'self' else coalesce(p ->> 'relation', 'other') end,
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
    case when (p ->> 'caregiver_attested')::boolean then now() end,
    v_self
  ) returning id into v_recipient;

  return v_recipient;
end $$;

-- Plan counts: an own profile is not one of the parents.
CREATE OR REPLACE FUNCTION public.limit_parents()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid;
  v_max integer;
begin
  if new.is_self then return new; end if;   -- your own profile never counts as one of the parents of your plan
  select created_by into v_owner from public.care_circles where id = new.circle_id;
  if v_owner is null then return new; end if;
  perform pg_advisory_xact_lock(hashtext('parents:' || v_owner::text));
  select max_parents into v_max from public.plan_of_user(v_owner);
  if (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = v_owner and not r.is_self) >= coalesce(v_max, 20) then   -- 20 = system ceiling for "unlimited"
    raise exception 'plan limit: parents' using errcode = 'P0001', hint = 'plan_parents';
  end if;
  return new;
end $function$;
CREATE OR REPLACE FUNCTION public.paused_recipients()
 RETURNS TABLE(recipient_id uuid)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
  select x.id from (
    select r.id, c.created_by, row_number() over (partition by c.created_by order by r.created_at, r.id) as rn
      from public.care_recipients r join public.care_circles c on c.id = r.circle_id
     where not r.is_self
  ) x
  where x.created_by is not null
    and x.rn > coalesce((public.plan_of_user(x.created_by)).max_parents, 20)
$function$;
CREATE OR REPLACE FUNCTION public.plan_overview()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO ''
AS $function$
  select jsonb_build_object(
    'plan', p.plan,                                             -- what the account is on (free / plus / family / ultra)
    'effective', e.key, 'name', e.name,                         -- what applies right now (a trial counts as Plus)
    'max_parents', e.max_parents, 'max_members', e.max_members, 'max_medicines', e.max_medicines,
    'on_trial', (p.plan = 'free' and p.trial_ends_at > now()),
    'trial_ends_at', p.trial_ends_at,
    'parents_used', (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id and not r.is_self),
    'would_pause_parents', case when p.plan = 'free' and p.trial_ends_at > now() then
        greatest(0, (select count(*) from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id and not r.is_self) - (select max_parents from public.plans where key = 'free')) else 0 end,
    'would_pause_medicines', case when p.plan = 'free' and p.trial_ends_at > now() then coalesce((
        select sum(greatest(0, x.meds - (select max_medicines from public.plans where key = 'free')))
        from (select r.id, r.created_at,
                     row_number() over (order by r.created_at, r.id) as parent_rank,
                     (select count(*) from public.medicines m where m.recipient_id = r.id and m.active) as meds
                from public.care_recipients r join public.care_circles c on c.id = r.circle_id where c.created_by = p.id and not r.is_self) x
        where x.parent_rank <= (select max_parents from public.plans where key = 'free')), 0) else 0 end
  )
  from public.profiles p, lateral public.plan_of_user(p.id) e
  where p.id = (select auth.uid())
$function$;

-- Love messages are for parents.
create or replace function public.set_love_enabled(p_recipient uuid, p_enabled boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare v_group text;
begin
  if not public.can_access_recipient(p_recipient) then raise exception 'not found' using errcode = '42501'; end if;
  if exists (select 1 from public.care_recipients r where r.id = p_recipient and r.is_self) then
    raise exception 'love_not_available' using errcode = 'P0001';
  end if;
  select pilot_group into v_group from public.love_settings where recipient_id = p_recipient;
  if coalesce(p_enabled, false) and v_group = 'control' then
    raise exception 'love_not_available' using errcode = 'P0001';
  end if;
  insert into public.love_settings (recipient_id, enabled, first_enabled_at)
  values (p_recipient, coalesce(p_enabled, false), case when coalesce(p_enabled, false) then now() end)
  on conflict (recipient_id) do update
     set enabled = excluded.enabled,
         first_enabled_at = coalesce(public.love_settings.first_enabled_at, excluded.first_enabled_at);
end $$;
