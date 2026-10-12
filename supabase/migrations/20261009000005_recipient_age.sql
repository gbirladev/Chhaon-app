-- The Figma profile form asks for age, not date of birth. Stored as-is for v1; revisit when refactoring.
alter table public.care_recipients
  add column age smallint check (age between 0 and 130);

create or replace function public.create_care_recipient(p jsonb) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_circle uuid;
  v_recipient uuid;
begin
  insert into public.care_circles (created_by) values ((select auth.uid()))
  returning id into v_circle;

  insert into public.care_recipients (
    circle_id, name, relation, age, dob, city, whatsapp_phone, timezone, language,
    conditions, allergies, blood_group, doctor_contacts, emergency_contacts, caregiver_attested_at
  ) values (
    v_circle,
    p ->> 'name',
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
