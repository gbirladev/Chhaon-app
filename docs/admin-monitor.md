# Admin Monitor

An owner-only screen in the app (Family tab -> **Monitor**, shown only when `profiles.is_admin` is true) that shows every
client family and makes breakages obvious. Built after the 2026-11-05 incident: a WhatsApp number change left the new gateway
session without a webhook, so for about 12 hours no parent reply reached the backend while reminders still went out, and
nobody noticed. The same day two 14:00 / 14:15 doses went out at 14:31 (a scheduler gap).

## Access and privacy

- Every `/api/v1/admin/*` Monitor endpoint needs a signed-in account whose **database** flag `profiles.is_admin` is true
  (401 without login, 403 otherwise). The token's `admin` claim alone is not enough.
- **Phone numbers are masked on the server** (since 2026-11-06, `PhoneMask`): only the country code and the last 4 digits leave
  the backend, e.g. `+91 ••••• •0001`. This covers every field whose name ends in `phone` at any depth (parent WhatsApp number,
  caregiver and alert numbers, the reminder receiver's number, emergency and doctor contacts) in `/families`, `/families/{id}`,
  `/upcoming` and `/feedback-inbox`, and the gateway's logged-in number (also the canary SIM's, shown as `Test Papa`) in `/gateway`
  and the `gateway` health check. The full number never reaches the browser. Searching the families list by the last 4 digits
  still works. There are no call / WhatsApp links in the Monitor.
- Names, e-mail addresses and medicines (names, doses, schedules, conditions and allergies on the family page) are still shown:
  the owner needs them to help a family. Every family view is audited.
- Opening one family (`GET /api/v1/admin/families/{id}`) writes a row to `admin_audit` (admin id, recipient, circle, time).
- Nothing about families is written to the server log. The refused-webhook counter stores only a reason and a time.

## API (JSON, snake_case keys, UTC ISO times)

| Endpoint | What |
|---|---|
| `GET /api/v1/admin/health` | The checks below (green / amber / red, reason, facts, since), server build + uptime, WhatsApp volume, refused webhooks by reason |
| `GET /api/v1/admin/alerts?limit=30` | Open problems + history recorded by the 5-minute checker |
| `GET /api/v1/admin/overview?date=YYYY-MM-DD` | One India day (default today): doses sent / taken / taken late / missed / waiting / sent late, check-ins sent / answered / call-me, family alerts, message volume, plus the pilot stats (`admin_stats()`) |
| `GET /api/v1/admin/families` | One row per person cared for: caregivers, care type, WhatsApp number, reminder receiver, consent, plan, today x/y, last reply, median reply minutes (7 days), messages today / 7 days, last caregiver activity, warning badges |
| `GET /api/v1/admin/families/{recipientId}` | Everything about one person (profile, members, medicines + schedules, 7 days of doses and check-ins, alerts, consent requests, escalations). Audited |
| `GET /api/v1/admin/activity?limit=50` | Latest events across families, newest first |
| `GET /api/v1/admin/upcoming?hours=2` | Doses and check-ins due in the next hours, with receiver and whether they will really be sent |
| `GET /api/v1/admin/feedback-inbox?limit=50` | Caregiver feedback with who sent it; problem reports also carry `topic`, `context` (the diagnostic note the caregiver saw before sending) and `status` |
| `POST /api/v1/admin/feedback/{id}/status` `{"status":"new"\|"looking"\|"fixed"}` | "Report a problem" (migration 20261108000062): New -> Looking -> Fixed. Audited (`admin_audit.action` = `feedback_<status>`, `feedback_id`). The first Fixed puts "We fixed the problem you reported" in the sender's bell (in-app only, never WhatsApp). 409 before the migration |
| `GET /api/v1/admin/gateway?fresh=true` | The gateway probe (skips the 60 s cache) |

Code: `api-java/src/main/java/org/smartgurukul/chhaon/admin/`. Web: `web/src/screens/Monitor.tsx`, `web/src/lib/admin.ts`.

## The checks, and what to do when one is red

| Check | Green / amber / red | When it is not green |
|---|---|---|
| **Reminder job** (`scheduler`) | red when the last tick (`scheduler_heartbeat`) is more than 10 min old; amber when no tick was ever recorded | The backend is down or `CHHAON_SCHEDULER_ENABLED` is not `true`. Check the service (`systemctl status chhaon-backend`) and the env file. |
| **On time** (`reminders_on_time`) | red when a dose due more than 15 min ago is still `pending`; amber when a dose was sent more than 15 min late today, or a `reminder_not_sent` happened in 24 h | Look at the backend log for `scheduler step ... failed` / `send failed`; check the gateway is connected. Late sends mean the tick ran late (deploy, restart, slow gateway). |
| **Replies coming in** (`replies_in`) | red when 2+ reminders went out in the last 3 h (older than 15 min) and **no** inbound message (`whatsapp_events` id `gw:in:%`) arrived in 3 h; amber when the same is true for 24 h | This is the 2026-11-05 incident. Open the gateway dashboard: is the session connected, and does it have an active webhook to `https://<api>/webhooks/whatsapp-gateway` with `message.received`? Also check **Webhook calls**. |
| **Canary** (`canary`) | The test family (docs/canary.md): green when the latest test reminder that was answered or is older than 10 min was answered on WhatsApp within 10 min; amber when answered later; red when it got no answer within 10 min (or was marked missed), or when no test reminder went out for 5 h; **grey** (never an alert) when no canary is set up, or it was set up less than 5 h ago and nothing has gone out yet. Own card: last send, answer, round trip, "x of y in time" (24 h), test messages today | Red "no answer": look at the other cards first, then the spare phone (on, online, Canary session connected, auto-reply rule). Red "not sent": the reminder job or the send is broken. |
| **WhatsApp gateway** (`gateway`) | red when unreachable, no session named `WHATSAPP_SENDER_SESSION_NAME`, the session is not `CONNECTED` (logged out), or it has **no active webhook** for `message.received`; amber when not configured (dry-run) or the webhook list could not be read | Reconnect the session (scan the QR code) and (re)create the webhook with the same secret as `WHATSAPP_WEBHOOK_SECRET`. The card shows the logged-in number and the last successful send. |
| **Webhook calls** (`webhook_rejections`) | amber when our server refused any gateway webhook call in the last hour; red when calls were refused and none got through | Reasons: wrong/missing signature (401: the secrets differ), no secret set (503: `WHATSAPP_WEBHOOK_SECRET` empty on the server), server error (500: database problem, see the log), bad request (400/413). |
| **Failed sends** (`failed_sends`) | amber when the gateway reported a reminder as not delivered (`whatsapp_failed`) or family alerts keep failing in 24 h | Open the family: number changed, blocked, or not on WhatsApp? |
| **Message volume** (`whatsapp_volume`) | amber above 200 messages today (`chhaon.admin.volume-amber-per-day`) or more than 3x the 7-day average (with at least 30 today) | The number is an unofficial gateway number: high volume risks a ban. Find the family that causes it (Families tab, "msgs today"). |
| **Consent** (`consent_pending`) | amber when someone has not said yes on WhatsApp for more than a day. Demo families are left out here and in the `consent_pending` badge (`DemoFamilies`): WhatsApp numbers in the fake test range +91 99999 xxxxx, and circles whose owner signs in with the store review phone (`OTP_REVIEW_PHONE`). They are not the canary and nothing about them is changed | No reminders reach them. Ask the family to have the person reply. |
| **Low stock** (`low_stock`) | amber when an active medicine is at or below its refill level | Families see this too; nothing to fix on our side. |

Volume counts every send the database records: dose reminders, check-ins, family alerts on WhatsApp, appointment reminders,
consent requests, weekly summaries, reactions (vaccine reminders keep only a date and are not counted). The canary's reminders are
real messages from our number, so they count here too, and are also shown on their own (`volume.canary_today`).

**The canary family** (`care_recipients.is_canary`, `profiles.is_canary`, migration `20261106000055_canary.sql`, docs/canary.md) is
left out of the pilot stats (`admin_stats()`), the day's totals in `/overview` (doses), `/activity`, weekly summaries and every
family notification (a database trigger drops any notification for the canary caregiver or about the canary person). It stays in
`/families` (last row, `is_canary: true`, badge `canary`), `/upcoming`, the "Replies coming in" check (its replies really do prove
the webhook works) and "On time" (a stuck canary reminder is a real scheduler problem). A check status `grey` means "nothing to
watch": it never opens an alert and does not change the overall level.

## Alerts (dashboard only)

`AdminHealthChecker` runs every 5 minutes (`chhaon.admin.health-checker.enabled`, default on; off in tests). A non-green check
opens one `admin_alerts` row (one open row per check), keeps it current while it lasts, and resolves it when the check is green
again. Opening and resolving call `AdminAlertNotifier`; today that is `NoopAdminAlertNotifier` (nothing is sent). To add e-mail,
add a bean implementing the interface with `@Primary` (see docs/TODO.md: Resend).

## Server version

`ServerInfo` reads `META-INF/build-info.properties` (spring-boot-maven-plugin `build-info`); the deploy workflow passes
`-Dchhaon.git-sha=$GITHUB_SHA`. Fallback: env `GIT_SHA`, else "unknown".

## Database (migration `20261105000054_admin_monitor.sql`)

`admin_alerts`, `admin_audit`, `admin_webhook_rejections`: RLS on with no policies and no grants for `anon` / `authenticated`
(service role only, like `whatsapp_events`). Plain JDBC, no JPA entities.

**Production order:** paste `supabase/migrations/20261105000054_admin_monitor.sql` into the Sydney SQL Editor **first** (safe to run
twice), then push / deploy the backend. The same for the canary: `20261106000055_canary.sql` first, then deploy, then
`supabase/maintenance/canary_setup.sql` (docs/canary.md). Without that migration the Canary check shows amber "check_failed"
(the reminder sending, weekly summaries and the Monitor's lists read the flag defensively and keep working). Optionally re-run `supabase/maintenance/restore_table_permissions.sql` (it now includes
the three tables).
