-- Step 2: parent profile + medicine screens.

alter table public.care_recipients
  add column relation text not null default 'other' check (relation in ('father', 'mother', 'other')),
  add column city text,
  -- Caregiver confirms the parent agrees to WhatsApp messages (DPDP record).
  -- The parent's own opt-in is consent_at, set only by the backend.
  add column caregiver_attested_at timestamptz;

alter table public.medicines
  add column instructions text check (instructions in ('after_food', 'before_food', 'with_food')),
  -- Remind caregivers when stock_count falls to this level. NULL = no refill reminder.
  add column refill_threshold integer check (refill_threshold >= 0);

-- ---------------------------------------------------------------- create_care_recipient
-- Creates a circle (caller becomes owner via trigger) and the parent atomically.
-- SECURITY INVOKER: RLS still applies to every insert.
create function public.create_care_recipient(p jsonb) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_circle uuid;
  v_recipient uuid;
begin
  insert into public.care_circles (created_by) values ((select auth.uid()))
  returning id into v_circle;

  insert into public.care_recipients (
    circle_id, name, relation, dob, city, whatsapp_phone, timezone, language,
    conditions, allergies, blood_group, doctor_contacts, emergency_contacts, caregiver_attested_at
  ) values (
    v_circle,
    p ->> 'name',
    coalesce(p ->> 'relation', 'other'),
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

-- ---------------------------------------------------------------- create_medicine
-- Medicine + one schedule row per time, atomically.
create function public.create_medicine(
  p_recipient uuid,
  p_name text,
  p_dosage text,
  p_instructions text,
  p_photo_url text,
  p_stock integer,
  p_refill_threshold integer,
  p_times time[],
  p_start date default current_date,
  p_end date default null
) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_medicine uuid;
begin
  if coalesce(cardinality(p_times), 0) = 0 then
    raise exception 'at least one reminder time is required' using errcode = '22023';
  end if;

  insert into public.medicines (recipient_id, name, dosage, instructions, photo_url, stock_count, refill_threshold)
  values (p_recipient, p_name, p_dosage, p_instructions, p_photo_url, p_stock, p_refill_threshold)
  returning id into v_medicine;

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date, end_date)
  select v_medicine, t, p_start, p_end from unnest(p_times) as t;

  return v_medicine;
end $$;

revoke execute on function public.create_care_recipient(jsonb) from public, anon;
revoke execute on function public.create_medicine(uuid, text, text, text, text, integer, integer, time[], date, date) from public, anon;
grant execute on function public.create_care_recipient(jsonb) to authenticated;
grant execute on function public.create_medicine(uuid, text, text, text, text, integer, integer, time[], date, date) to authenticated;
