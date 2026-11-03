-- Public emergency card, answered by the database (restores step 10 after the move to the Java backend).
--
-- The card used to be served by the Python API (GET /public/emergency/<token>, service role). That API is no longer
-- deployed and the Java backend does not have the route, so the public page showed "Could not load" for every link.
-- The page now asks the database directly, as the anon role, through the one function below. It returns exactly what
-- the Python route returned: the bare minimum a paramedic needs and nothing else (no address or city, no date of
-- birth, no stock levels, no reports, no parent phone unless the caregiver listed it as an emergency contact).
--
-- Anyone holding the 64-hex token may call it, so:
--  * the token must be 64 lowercase hex characters, else the answer is the same NULL as for an unknown, revoked or
--    deleted link (nothing to learn from the answer);
--  * the function never raises on bad input and never logs;
--  * the table itself stays closed to anon (no select), and so does the view counter function.
-- Not done here: a per-IP rate limit (the Python route allowed 30 a minute). A 256-bit token cannot be guessed, but if
-- guessing traffic ever shows up, add the limit at the edge (CDN / WAF) or in the Java service.

-- Hidden characters that can reorder or hide text on a screen (bidi controls, zero-width space, BOM, soft hyphen;
-- ZWJ/ZWNJ stay because Hindi needs them), control characters turned into a space, trimmed and cut to a length.
create or replace function public.card_clean(p_value text, p_limit integer default 120) returns text
language sql immutable set search_path = '' as $$
  select left(
    regexp_replace(
      regexp_replace(
        regexp_replace(coalesce(p_value, ''), '[‪-‮⁦-⁩‎‏؜​⁠﻿­]', '', 'g'),
        '[\x01-\x1f\x7f  ]+', ' ', 'g'),
      '^[\s   -   　]+|[\s   -   　]+$', '', 'g'),
    p_limit);
$$;
revoke all on function public.card_clean(text, integer) from public, anon, authenticated;

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
    'doctor',      v_doctor
  );
end $$;

revoke all on function public.public_emergency_card(text) from public, anon, authenticated;
grant execute on function public.public_emergency_card(text) to anon, authenticated;
comment on function public.public_emergency_card(text) is
  'Public (no login) emergency card by token: the minimum a paramedic needs. NULL for unknown, revoked, malformed or deleted links.';
