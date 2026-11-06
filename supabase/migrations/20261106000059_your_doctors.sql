-- "Your doctors" (2026-11-06): the family's own list of the person's doctors on the public emergency card.
--
-- Sydney: paste into the SQL Editor BEFORE deploying the web app (the public card page asks for this function; an older page
-- does not). The backend does not need it. Safe to run twice. Needs 0038 (card_clean, emergency_links) first.
--
-- The list itself needs no schema change: care_recipients.doctor_contacts (jsonb, since the first schema) now holds up to 5
-- doctors as {"name", "speciality"?, "clinic"?, "phone"?, "address"?}, written only by the Java backend
-- (PUT /api/v1/recipients/{id}/doctors), which checks every field and the phone number.
--
-- public_emergency_doctors(token): the doctors for the same token as public_emergency_card, kept as a SEPARATE function
-- on purpose, so public_emergency_card is not replaced here (other changes to it can land without a merge). It returns
-- only name, speciality and phone (no clinic, no address: the card shows the minimum a paramedic needs), at most 5, rows
-- without a name left out, a phone only when it is a valid international number. Same rules as the card: NULL for an
-- unknown, revoked, malformed or deleted link (nothing to learn from the answer); never raises; does not count as a view
-- (the card call already counted it).
--
-- Grants: Supabase gives every new function to anon by default, so everything is revoked first and anon + authenticated
-- get it back on purpose (the public page calls it with the anon key, like the card). Add it to
-- supabase/maintenance/restore_function_permissions.sql (done in this change).

create or replace function public.public_emergency_doctors(p_token text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_token    text := lower(btrim(coalesce(p_token, '')));
  v_list     jsonb;
  v_out      jsonb := '[]'::jsonb;
  v_item     jsonb;
  v_name     text;
  v_phone    text;
  v_phone_re constant text := '^\+[1-9][0-9]{7,14}$';
begin
  if v_token !~ '^[0-9a-f]{64}$' then
    return null;
  end if;
  select r.doctor_contacts into v_list
    from public.emergency_links l join public.care_recipients r on r.id = l.recipient_id
   where l.token = v_token and l.active
   limit 1;
  if not found then
    return null;
  end if;
  for v_item in select e from jsonb_array_elements(case when jsonb_typeof(v_list) = 'array' then v_list else '[]'::jsonb end) e loop
    exit when jsonb_array_length(v_out) >= 5;
    -- An old list may hold plain strings (a name only).
    v_name := case jsonb_typeof(v_item)
      when 'object' then public.card_clean(case when jsonb_typeof(v_item -> 'name') = 'string' then v_item ->> 'name' end, 60)
      when 'string' then public.card_clean(v_item #>> '{}', 60)
      else '' end;
    continue when v_name = '';
    v_phone := case when jsonb_typeof(v_item) = 'object' and jsonb_typeof(v_item -> 'phone') = 'string'
                    then public.card_clean(v_item ->> 'phone', 20) end;
    v_out := v_out || jsonb_build_array(jsonb_build_object(
      'name',       v_name,
      'speciality', nullif(case when jsonb_typeof(v_item) = 'object' and jsonb_typeof(v_item -> 'speciality') = 'string'
                                then public.card_clean(v_item ->> 'speciality', 40) end, ''),
      'phone',      case when v_phone ~ v_phone_re then v_phone end));
  end loop;
  return v_out;
exception when others then
  return null;
end $$;

revoke all on function public.public_emergency_doctors(text) from public, anon, authenticated;
grant execute on function public.public_emergency_doctors(text) to anon, authenticated;
comment on function public.public_emergency_doctors(text) is
  'Public (no login) list of the person''s doctors for an emergency card token: name, speciality, phone (at most 5). NULL for unknown, revoked, malformed or deleted links.';
