# Pilot cap

Chhaon sends every WhatsApp message from one number through an unofficial gateway. What gets such a number banned is
messaging many **new** contacts in a short time and sudden jumps in volume. So while we are in pilot:

- At most **50 WhatsApp numbers** get messages (`max_active`).
- At most **5 new numbers** are let in per day (`max_new_per_day`).
- The scheduler leaves a random **3-8 second gap** between its sends (reminders, check-ins), so 08:00 is a trickle, not a burst.

Sign-up stays open. A family over the cap sets up everything as usual; only the first WhatsApp message (the consent request)
waits. The app shows "Chhaon is in pilot... You're #N in line". Every 10 minutes (when the scheduler runs) the backend lets the
first people in line in while there is room: it sends their consent request and the caregiver gets a notification
("Good news: it's Papa's turn").

## What counts

- A **seat** is a WhatsApp number (not the canary, not stopped) that agreed, or was let in during the last 7 days and has not
  answered yet. Two parents sharing one phone use one seat; a second parent on a number that already agreed never waits.
- Someone let in keeps their place (asking again, a changed number, a failed send).
- STOP frees the seat. The next in line gets it, never whoever happens to press the button.
- Families who agreed before the cap existed simply count as seats. Nobody already receiving reminders is affected.

## The owner's controls

The **Monitor > Pilot** tab shows the numbers and the line (names only), with **Move to front** for a family you want in
first (friends, a doctor's patient). The numbers are changed in the Supabase SQL Editor:

```sql
-- more room, or a faster daily pace
update public.pilot_settings set max_active = 80, max_new_per_day = 8, updated_at = now();

-- switch the cap off (everyone is let in at once on their next request / the next 10-minute run)
update public.pilot_settings set enabled = false, updated_at = now();

-- see the line
select public.pilot_summary();
```

The gap between sends is a backend setting (`chhaon.whatsapp.pace-min-ms` / `pace-max-ms`, default 3000 / 8000; 0 / 0 = off).

## Switching it on (Sydney)

1. SQL first: paste `supabase/migrations/20261107000060_pilot_cap.sql` into the SQL Editor (after 0059 your doctors). Safe to run twice.
2. Then push the backend. A backend deployed before the SQL keeps working as before (no cap) because it checks that the
   functions exist.
3. Local Java tests: the test database is a schema-only copy of the local `postgres` database, so apply the migration locally
   and re-run `api-java/scripts/make-test-db.sh`. A fresh test database has no settings row, which means "cap off"; the pilot
   tests create and remove their own row.

Code: `pilot_admit` / `pilot_position` / `pilot_summary` / `pilot_move_up` (migration 0060), `PilotService`,
`PilotAdmissionService`, `SendPacer`, the gate in `ConsentService.requestConsent`, the consent notice in `web/src/App.tsx`
and the Monitor's `PilotCapCard`. Tests: `supabase/tests/pilot_cap.test.sql`, `PilotCapTest`, `SendPacerTest`.
