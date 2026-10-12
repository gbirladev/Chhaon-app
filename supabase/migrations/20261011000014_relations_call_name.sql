-- More relations, and the name the caregiver actually uses for their parent.

alter table public.care_recipients drop constraint if exists care_recipients_relation_check;
alter table public.care_recipients
  add constraint care_recipients_relation_check
  check (relation in ('father', 'mother', 'grandfather', 'grandmother', 'father_in_law', 'mother_in_law',
                      'spouse', 'uncle', 'aunt', 'brother', 'sister', 'other'));

-- What the caregiver calls them ("Mom", "Appa", "Dadi"): used in alerts and in the WhatsApp messages the parent receives.
-- NULL = use the old default for the relation (Papa / Mummy) or the name.
alter table public.care_recipients
  add column if not exists call_name text check (char_length(call_name) <= 40);

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
