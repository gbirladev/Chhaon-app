-- WhatsApp alerts to the CAREGIVER (missed dose, no check-in reply, low stock...).
-- Double opt-in: the number must reply "Yes" to a message from us before any alert is sent to it.

alter table public.profiles
  add column alert_phone         text check (alert_phone ~ '^\+[1-9][0-9]{7,14}$'),
  add column alert_requested_at  timestamptz,   -- when we last asked this number to confirm
  add column alert_confirmed_at  timestamptz,   -- the number replied Yes at least once (lets START restore alerts after STOP)
  add column alert_consent_at    timestamptz;   -- alerts are ON (cleared by turning them off in the app, or STOP)

-- Only the system may set the number and its confirmation. A caregiver may only turn alerts OFF.
create function public.guard_profile_alerts() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated' then
    if new.alert_phone is distinct from old.alert_phone
       or new.alert_requested_at is distinct from old.alert_requested_at
       or new.alert_confirmed_at is distinct from old.alert_confirmed_at
       or (new.alert_consent_at is distinct from old.alert_consent_at and new.alert_consent_at is not null) then
      raise exception 'alert settings can only be changed by the system (you may turn alerts off)' using errcode = '42501';
    end if;
  end if;
  return new;
end $$;
create trigger profiles_guard_alerts before update on public.profiles
  for each row execute function public.guard_profile_alerts();

-- Delivery bookkeeping on notifications (set by the backend only).
alter table public.notifications
  add column whatsapp_sent_at     timestamptz,
  add column whatsapp_skipped_at  timestamptz,
  add column whatsapp_attempts    integer not null default 0;

-- Everything that exists today predates alerts: never send it.
update public.notifications set whatsapp_skipped_at = now();

create index notifications_alert_outbox on public.notifications (created_at)
  where whatsapp_sent_at is null and whatsapp_skipped_at is null;

-- A caregiver may mark their own notifications read, but nothing else on them.
revoke update on public.notifications from authenticated;
grant update (read_at) on public.notifications to authenticated;
