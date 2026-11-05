-- Canary family: create (or update) the dummy family that proves every 4 hours that reminders and replies work.
-- Plain-English steps: docs/canary.md.
--
-- BEFORE: supabase/migrations/20261106000055_canary.sql has been pasted into this SQL Editor (once), and the backend that
-- knows about the canary is deployed.
--
-- HOW: replace +9199999xxxxx below with the spare SIM's WhatsApp number (E.164: +, country code, number, no spaces),
-- then press Run. Safe to run again (same family, updated); running it with a new number moves the canary to that number.
--
-- It creates: caregiver "Chhaon Canary" (dummy account, no login, no alerts), person "Test Papa" on that number
-- (English, India time, consent already given, no daily check-in), medicine "Test tablet" (no stock tracking, no photo)
-- at 02:00, 06:00, 10:00, 14:00, 18:00 and 22:00 India time. The first reminder goes out at the next of those times.

select public.canary_setup('+9199999xxxxx') as canary_recipient_id;

-- What was created (one row each, 6 times):
select r.name, r.whatsapp_phone, r.timezone, r.language, r.consent_at is not null as consent_given, r.checkin_enabled,
       r.reminder_to, m.name as medicine, m.stock_count, array_agg(s.time_of_day order by s.time_of_day) as times
  from public.care_recipients r
  join public.medicines m on m.recipient_id = r.id
  join public.medicine_schedules s on s.medicine_id = m.id
 where r.is_canary
 group by r.id, m.id;
