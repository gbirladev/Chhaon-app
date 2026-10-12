# Scheduler (step 4)

One **tick** runs every 5 minutes. It is safe to run more often or twice at once: every step claims rows in the database first.

## What a tick does
| Step | Rule |
|---|---|
| 1. Create dose events | SQL function `generate_dose_events` makes events for the next 24 hours from each active schedule, using the **parent's timezone** (stored as UTC). Never before the schedule existed. Pending doses of paused medicines are removed. |
| 2. Send reminders | Pending doses that are due (and less than 60 min late) are sent as WhatsApp templates. A parent who has not agreed, or said STOP, gets nothing and the dose becomes `skipped` (not "missed"). A failed send stays pending and is retried every tick. |
| 3. Unsent reminders | A reminder that kept failing until the window closed becomes `skipped`, and the family gets `reminder_not_sent` ("please call"). |
| 4. Missed doses | A reminder sent more than 60 min ago with no "Taken" tap becomes `missed`, and the family gets `dose_missed` once. |
| 5. Daily check-in | At the parent's local `checkin_time` (default 08:00), within a 2-hour window (so a late scheduler never sends "good morning" at 3 pm), one check-in per local day. |
| 6. Missed check-in | No reply after 3 hours: `checkin_missed` to the family. |
| 7. Appointment reminders | For appointments with `remind = true`, in the **parent's timezone**: a day-before reminder at/after 18:00 the evening before (until 07:30 on the visit day), and a morning reminder at/after 07:30 on the visit day while the visit is at least 30 min away (so an appointment booked later that morning is reminded at the next tick). Template `carelink_appointment_reminder`. Each is claimed first (`reminded_day_before_at` / `reminded_morning_at`), rolled back and retried if the send fails, and never sent without consent or after STOP. Each sent reminder creates one `appointment_reminder` notification (bell + caregiver WhatsApp alert `carelink_alert_appointment`). Code: `api/app/services/appointments.py`. |

Low-stock alerts are not part of the tick. A database trigger raises `refill_needed` instantly when stock reaches the refill level (a dose confirmed on WhatsApp uses up stock, or the caregiver edits it), once per drop.

Windows are settings in `api/app/config.py`: `dose_missed_minutes` (60), `checkin_missed_minutes` (180), `checkin_start_window_minutes` (120).

## Running it
- **Production:** any cron service calls `POST https://<api>/cron/tick` every 5 minutes with header `X-Cron-Secret: <CRON_SECRET>`.
  Examples: a Vercel Cron job, a GitHub Actions schedule, Supabase `pg_cron` + `pg_net`, or Render/Railway cron.
  The endpoint refuses to run without `CRON_SECRET` set.
- **Command line / local loop:** `cd api && uv run python -m app.tick` (once) or `uv run python -m app.tick --every 60` (loop).
- **Tests:** `cd api && uv run pytest tests/test_scheduler.py` (controlled clock, no waiting).

## Behaviour worth knowing (added after QA)
- **Catch-up:** if the tick was down, doses created up to 60 minutes late are still sent; older ones become `skipped` and the family gets `reminder_not_sent`.
- **Opted-out parents** (STOP) never get "missed" or "no check-in" alerts: they are not ignoring us.
- **Timezone changes** delete the parent's pending future doses; the next tick recreates them in the new zone. Unknown timezone names are rejected when saved, and skipped (not fatal) when generating.
- **One bad item never stops the tick:** an error on one reminder is logged and counted (`send_errors`); the dose is handed back to `pending` and retried.
- **Large lists:** every scheduler query pages through results (PostgREST returns at most 1000 rows per request).
- **Medicine times** are unique per medicine (one reminder per clock time).

## Alerts the family sees
Notifications are rows in `notifications` (ids only, never phone numbers or health text). The app shows them on the bell on Today and refreshes every 30 seconds.
Types: `dose_missed`, `reminder_not_sent`, `checkin_missed`, `checkin_call_requested`, `refill_needed`, `appointment_reminder`, `parent_consented`, `parent_declined`, `parent_stopped`, `parent_resumed`, `whatsapp_failed`.

## Not done yet
- Alerts outside the app (WhatsApp message to the caregiver, push, email). See `docs/TODO.md`.
- Editing a medicine's times (the schedule UI only creates them), and refill reminders for appointments (step 8).
