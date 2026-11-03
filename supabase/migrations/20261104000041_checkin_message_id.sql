-- Check-in messages remember the WhatsApp message id they were sent with (like dose_events.whatsapp_message_id).
--
-- WHY: the new WhatsApp route (self-hosted gateway) has no quick-reply buttons, so a parent answers by TYPING. When the
-- parent swipes "reply" on a message, WhatsApp tells us which message was quoted; matching that id to the dose reminder or the
-- daily check-in it was sent as tells us exactly what the answer is for ("1" under the morning check-in is not "Taken").
-- Doses already had the column; check-ins did not.
--
-- The Java code does not depend on this column to run: it stores the id best-effort and only looks quotes up when the column exists.
alter table public.checkins add column if not exists whatsapp_message_id text;
create index if not exists checkins_whatsapp_message_id_idx
  on public.checkins (whatsapp_message_id) where whatsapp_message_id is not null;
