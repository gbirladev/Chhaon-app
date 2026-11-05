-- Round 2 (R3, "facts that must be right"): a child's weight, and the key facts on the public emergency card.
--
-- Sydney: paste into the SQL Editor BEFORE deploying the backend (the backend reads and writes weight_kg / weight_on for a
-- child, and the web app reads the two new keys of the card). Safe to run twice. Needs 0038 (card), 0043 (care_type, due_date)
-- and 0045 (child) first. Table grants are table-wide, so nothing to re-grant (table_permissions_check.sql stays the same).
--
-- 1. care_recipients.weight_kg + weight_on: the last weight the family recorded for a child, and the day it was measured.
--    A plain recorded fact (no chart, no judgement, no advice). Between 0.5 and 200 kg; a date only together with a weight.
--    Saved by the Java backend (PUT /api/v1/recipients/{id}/child/weight, guardian confirmation required like all child data).
-- 2. public_emergency_card: adds ONLY
--      pregnancy: {week, due_date}           for a pregnancy (week in the person's time zone; null when out of range)
--      child:     {born, weight_kg, weight_on} for a child (and then 'age' is null: "Born 8 Jun 2026 (5 months)" replaces it)
--    Both are null for everyone else. Nothing else changes: still the minimum a paramedic needs, still NULL for an unknown,
--    revoked or malformed link, still the same anon grant.

alter table public.care_recipients add column if not exists weight_kg numeric(5, 2);
alter table public.care_recipients add column if not exists weight_on date;

alter table public.care_recipients drop constraint if exists care_recipients_weight_sane;
alter table public.care_recipients add constraint care_recipients_weight_sane check (
  (weight_kg is null or weight_kg between 0.5 and 200)
  and (weight_on is null or (weight_kg is not null and weight_on >= date '1900-01-01' and weight_on <= current_date + 1))
);

comment on column public.care_recipients.weight_kg is 'Last weight the family recorded (kg, 0.5-200), shown on a child''s emergency card and doctor summary. A fact, never interpreted';
comment on column public.care_recipients.weight_on is 'The day weight_kg was measured (null = not said)';

create or replace function public.public_emergency_card(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_token    text := lower(btrim(coalesce(p_token, '')));
  v_link     public.emergency_links%rowtype;
  v_rec      public.care_recipients%rowtype;
  v_today    date := (now() at time zone 'utc')::date;
  v_phone_re constant text := '^\+[1-9][0-9]{7,14}$';
  v_meds     jsonb;
  v_contacts jsonb := '[]'::jsonb;
  v_doctor   text;
  v_age      jsonb;
  v_item     jsonb;
  v_phone    text;
  v_label    text;
  v_local    date;
  v_days     integer;
  v_preg     jsonb;
  v_child    jsonb;
  m          record;
begin
  if v_token !~ '^[0-9a-f]{64}$' then
    return null;
  end if;
  select * into v_link from public.emergency_links where token = v_token and active limit 1;
  if not found then
    return null;
  end if;
  select * into v_rec from public.care_recipients where id = v_link.recipient_id;
  if not found then
    return null;
  end if;

  -- Active medicines, oldest first (at most 40), with the times of the schedules that have not ended.
  select coalesce(jsonb_agg(jsonb_build_object(
           'name',  public.card_clean(x.name),
           'dose',  public.card_clean(x.dosage),
           'times', (select coalesce(jsonb_agg(t.hhmm order by t.hhmm), '[]'::jsonb)
                       from (select distinct to_char(s.time_of_day, 'HH24:MI') as hhmm
                               from public.medicine_schedules s
                              where s.medicine_id = x.id and (s.end_date is null or s.end_date >= v_today)) t)
         ) order by x.created_at), '[]'::jsonb)
    into v_meds
    from (select id, name, dosage, created_at from public.medicines
           where recipient_id = v_rec.id and active order by created_at limit 40) x;

  -- Emergency contacts the caregiver listed (valid international numbers only), then the family's own phones, at most 4.
  for v_item in select e from jsonb_array_elements(
      case when jsonb_typeof(v_rec.emergency_contacts) = 'array' then v_rec.emergency_contacts else '[]'::jsonb end) e loop
    if jsonb_typeof(v_item) = 'object' then
      v_phone := public.card_clean(case when jsonb_typeof(v_item -> 'phone') = 'string' then v_item ->> 'phone' end, 20);
      if v_phone ~ v_phone_re then
        v_contacts := v_contacts || jsonb_build_array(jsonb_build_object(
          'name', public.card_clean(case when jsonb_typeof(v_item -> 'name') = 'string' then v_item ->> 'name' end),
          'phone', v_phone));
      end if;
    end if;
  end loop;
  for m in select p.name as pname, p.phone as pphone
             from public.care_circle_members cm join public.profiles p on p.id = cm.user_id
            where cm.circle_id = v_rec.circle_id order by cm.created_at loop
    v_phone := public.card_clean(m.pphone, 20);
    if v_phone ~ v_phone_re and not exists (select 1 from jsonb_array_elements(v_contacts) c where c ->> 'phone' = v_phone) then
      v_contacts := v_contacts || jsonb_build_array(jsonb_build_object('name', public.card_clean(m.pname), 'phone', v_phone));
    end if;
  end loop;
  select coalesce(jsonb_agg(c order by o), '[]'::jsonb) into v_contacts
    from jsonb_array_elements(v_contacts) with ordinality as t(c, o) where o <= 4;

  -- The first doctor with a name.
  for v_item in select e from jsonb_array_elements(
      case when jsonb_typeof(v_rec.doctor_contacts) = 'array' then v_rec.doctor_contacts else '[]'::jsonb end) e loop
    v_label := case jsonb_typeof(v_item)
      when 'object' then public.card_clean(case when jsonb_typeof(v_item -> 'name') = 'string' then v_item ->> 'name' end)
      when 'string' then public.card_clean(v_item #>> '{}')
      else '' end;
    if v_label <> '' then
      v_doctor := v_label;
      exit;
    end if;
  end loop;

  v_age := case
    when v_rec.age is not null then to_jsonb(v_rec.age)
    when v_rec.dob is not null then to_jsonb(extract(year from age(v_today, v_rec.dob))::integer)
    else 'null'::jsonb end;

  -- Today on the person's own clock (the pregnancy week turns over at their midnight); an unknown zone falls back to India.
  begin
    v_local := (now() at time zone coalesce(nullif(v_rec.timezone, ''), 'Asia/Kolkata'))::date;
  exception when others then
    v_local := (now() at time zone 'Asia/Kolkata')::date;
  end;

  -- A pregnancy: the week (completed weeks, counted back from the due date like web/src/lib/pregnancy.ts and
  -- api-java recipient/Pregnancy.java; null before week 1 or after week 42) and the due date. Nothing else about it.
  if v_rec.care_type = 'pregnant' and not v_rec.is_self then
    v_days := case when v_rec.due_date is null then null else 280 - (v_rec.due_date - v_local) end;
    v_preg := jsonb_build_object(
      'week',     case when v_days is null or v_days < 7 or v_days >= 301 then null else v_days / 7 end,
      'due_date', v_rec.due_date);
  end if;

  -- A child: the date of birth (the card shows the age in months for a baby) and the last recorded weight with its date
  -- (useful for any dose a doctor works out by weight). The age in years is left out: "Born ..." says it better.
  if v_rec.care_type = 'child' and not v_rec.is_self then
    v_child := jsonb_build_object(
      'born',      v_rec.dob,
      'weight_kg', v_rec.weight_kg,
      'weight_on', case when v_rec.weight_kg is null then null else v_rec.weight_on end);
    v_age := 'null'::jsonb;
  end if;

  update public.emergency_links set view_count = view_count + 1, last_viewed_at = now() where id = v_link.id;

  return jsonb_build_object(
    'name',        coalesce((regexp_split_to_array(public.card_clean(v_rec.name), '\s+'))[1], ''),
    'age',         v_age,
    'blood_group', nullif(public.card_clean(v_rec.blood_group, 5), ''),
    'conditions',  coalesce((select jsonb_agg(public.card_clean(c) order by o)
                               from unnest(v_rec.conditions) with ordinality as u(c, o) where o <= 30), '[]'::jsonb),
    'allergies',   coalesce((select jsonb_agg(public.card_clean(a) order by o)
                               from unnest(v_rec.allergies) with ordinality as u(a, o) where o <= 30), '[]'::jsonb),
    'medicines',   v_meds,
    'contacts',    v_contacts,
    'doctor',      v_doctor,
    'pregnancy',   v_preg,
    'child',       v_child
  );
end $$;

revoke all on function public.public_emergency_card(text) from public, anon, authenticated;
grant execute on function public.public_emergency_card(text) to anon, authenticated;
comment on function public.public_emergency_card(text) is
  'Public (no login) emergency card by token: the minimum a paramedic needs. NULL for unknown, revoked, malformed or deleted links.';
