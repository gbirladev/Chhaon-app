-- Step 3: WhatsApp. Consent bookkeeping.

alter table public.care_recipients
  add column consent_requested_at timestamptz;   -- last time the consent template was sent (rate limit)

-- The parent's opt-in belongs to a specific WhatsApp number. If a caregiver changes the number,
-- the new person has not agreed to anything: reset consent and require a fresh opt-in.
-- (Trigger name sorts after care_recipients_guard_consent, so the guard still sees the caregiver's
--  own edit and only this reset changes the consent fields.)
create function public.reset_consent_on_phone_change() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.whatsapp_phone is distinct from old.whatsapp_phone then
    new.consent_at := null;
    new.opted_out_at := null;
    new.consent_requested_at := null;
  end if;
  return new;
end $$;

create trigger care_recipients_reset_consent_on_phone_change
  before update on public.care_recipients
  for each row execute function public.reset_consent_on_phone_change();
