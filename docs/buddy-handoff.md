# Handoff for the backend owner (AWS and WhatsApp gateway)

Updated 2026-11-07 (first written 2026-11-04). What is done, what is open for you, and what is coming. Nothing secret is
in this file: secrets are exchanged privately, never in the repository or a chat group.

## Open tasks for you (in this order)

### 1. Deploy the gateway update: poll answers + 👍 reactions (about 30 minutes) — MOST IMPORTANT

Our pipeline does NOT deploy `whatsapp-gateway/`; only you can. The backend side is already live and waits for it.

**What it adds**
- **Poll answers:** parents answer a reminder by tapping a WhatsApp poll option instead of typing "1".
  - The poll route answers with the sent poll's id: `{"status":true, "messageId":"3EB0...", "data":{"key":{"id":"3EB0...", ...}}}`.
  - Every poll the gateway sends is kept for 30 days in a new table `SentPoll` (a vote can only be decrypted with the poll's secret),
    and the socket gets a `getMessage` that returns it.
  - A vote is decrypted and sent to the webhook as a NEW event `poll.vote`. It goes to every webhook with `poll.vote` OR
    `message.received` ticked, so the existing `Chhaon backend` webhook needs no change. Our Baileys (7.0.0-rc.9) has its own vote
    decryption switched off, so the gateway does it itself (it tries the phone and the LID form of both numbers).
- **👍 reactions:** a long-press 👍 on a reminder is forwarded as `message.received` with `type: "REACTION"` (today it is dropped).

Files: `src/lib/poll.ts`, `poll-decrypt.ts`, `poll.test.ts`, `reaction.ts`, `reaction.test.ts`, `src/modules/whatsapp/poll-votes.ts`,
`src/lib/webhook.ts`, `src/modules/whatsapp/store/index.ts`, `instance.ts`, the poll route, `prisma/schema.prisma` (model `SentPoll`),
`package.json` (`npm test`), and the event lists (dashboard, swagger, API docs).

Webhook body for one tap (example number):
```json
{"event":"poll.vote","sessionId":"<session>","timestamp":"2026-11-05T08:00:00.000Z",
 "data":{"pollKeyId":"3EB0POLL0000000001","from":"919999900001@s.whatsapp.net","remoteJid":"919999900001@s.whatsapp.net",
         "selectedOptions":["✅ Taken"],"selectedIndexes":[0],"timestamp":1793779200}}
```
A changed vote sends the new choice; a removed vote sends empty lists. Votes on polls the gateway did not send (or older than 30 days)
and votes in groups are ignored.

**Where it runs:** EC2 `i-06c5527278a02b5df` (Sydney), code in `/opt/whatsapp-gateway`, systemd service `chhaon-wa` (user `ec2-user`,
`Restart=always`), `.env` in that folder. The WhatsApp login is stored in the gateway database (schema `wa_akg` on Supabase Sydney),
not on disk, so the session stays linked through the update.

**When:** a quiet time, not within 30 minutes of a dose time (avoid around 08:00 and 20:00 IST). The gateway is off for 5-10 minutes;
a reminder due in that window is retried at the next tick.

**Steps** (AWS Console → EC2 → the instance → Connect → Session Manager, or your usual access)
```bash
# 1. stop and back up
sudo su - ec2-user
sudo systemctl stop chhaon-wa
cp -a /opt/whatsapp-gateway /opt/whatsapp-gateway.bak-$(date +%Y%m%d)

# 2. new code from the repo (main), keeping .env and node_modules; then install
#    (e.g. git archive of whatsapp-gateway/ uploaded to s3://chhaon-deploys-304485839057/, then:)
aws s3 cp s3://chhaon-deploys-304485839057/whatsapp-gateway-polls.tar.gz /tmp/gw.tar.gz --region ap-southeast-2
tar xzf /tmp/gw.tar.gz --strip-components=1 -C /opt/whatsapp-gateway
cd /opt/whatsapp-gateway && npm ci

# 3. new table + tests (prisma must only ADD SentPoll; if it mentions data loss, answer No and stop)
set -a; . ./.env; set +a
npx prisma db push && npx prisma generate
npm test                      # 18 tests pass

# 4. build and start (the build needs the .env for NEXT_PUBLIC_*; the memory cap protects the backend on this 2 GB box)
NODE_OPTIONS=--max-old-space-size=1024 npm run build
sudo systemctl start chhaon-wa
sudo systemctl status chhaon-wa --no-pager | head -5
sudo journalctl -u chhaon-wa -n 40 --no-pager
```
Rollback: `sudo systemctl stop chhaon-wa; rm -rf /opt/whatsapp-gateway; mv /opt/whatsapp-gateway.bak-<date> /opt/whatsapp-gateway; sudo systemctl start chhaon-wa`
(the extra `SentPoll` table can stay).

**Checks** (all yours: everything about polls is your task end to end)
1. `https://wa.chhaon.site`: the session is CONNECTED without a new QR.
2. Typed replies still work: a test parent sends `1` to an open reminder, the dose turns "taken".
3. Poll: send one to your own phone (dashboard chat, or the API with the `X-API-Key` header):
   ```
   curl -s -X POST "https://wa.chhaon.site/api/messages/<sessionId>/<91XXXXXXXXXX@s.whatsapp.net>/poll" \
     -H "X-API-Key: <gateway api key>" -H "Content-Type: application/json" \
     -d '{"question":"💊 Papa, time for Metformin (blue round tablet)","options":["✅ Taken","⏰ Later"],"selectableCount":1}'
   ```
   The answer contains a `messageId`. Tap **✅ Taken**: the webhook log shows `poll.vote` with that `pollKeyId` and `["✅ Taken"]`, and
   our backend answers **200** (a test poll that is not a reminder is simply ignored by the backend). Change the vote, then remove it.
4. Reaction: long-press an open reminder on a test parent's phone and tap 👍: `message.received` with `"type":"REACTION"`, the dose
   turns taken. ❤️ does nothing.
5. Do 3 and 4 once from Android and once from iPhone.

**Then switch polls on** (yours too; if you cannot edit the repo's GitHub secrets, ask the owner to add this one value): GitHub secret `WHATSAPP_POLLS_ENABLED` = `true`, then Actions → Backend Deploy → Run
workflow. From the next time slot reminders and the morning check-in go out as polls; if a poll cannot be sent the backend falls back to
the text reminder by itself. After switching on, watch the first real poll reminder: the parent taps ✅, the dose turns "taken" in
the app, and the parent gets "Thank you 🙏 Noted: ...". If polls misbehave, set the secret to `false` and run Backend Deploy again:
reminders go back to text.

**Is it done? YES, polls work (2026-11-07).** Gateway updated by the buddy; a test dose for the test parent Papa at 09:35 IST came
as a poll, and 3 poll answers reached the backend (last 09:39 IST). Still open: the same tap test on the other phone type
(Android / iPhone), the ⏰ Later and changed-vote cases, and the 👍 reaction (check 4). On Sydney, every poll answer is saved with an
id starting `gw:poll:`:
```sql
select count(*) as poll_answers, max(received_at) as last_poll_answer from public.whatsapp_events where id like 'gw:poll:%';
```
0 means polls were never answered: the gateway update is not deployed, or `WHATSAPP_POLLS_ENABLED` is not `true`.

**Report to the owner:** did each tap give a `poll.vote` with the right option (Android / iPhone)? If not, the gateway log lines with
`[Poll]` (for example "Could not decrypt a vote on our poll ..."), with phone numbers masked.

### 2. Why were two doses sent 30 minutes late on 2026-11-05?
The 14:00 and 14:15 IST doses (08:30 / 08:45 UTC) were sent at 14:31 IST; doses at 17:30 and 19:30 went out on time. The scheduler runs
every 5 minutes, so it looks like a ~30 minute gap. Please look at the logs around 08:25-09:05 UTC:
`sudo journalctl -u chhaon-backend --since "2026-11-05 08:20" --until "2026-11-05 09:10" --no-pager | grep -iE "tick|scheduler|error|start"`
(was the backend restarting or deploying, was the database slow, or was the gateway down so sends failed and were retried?).

### 3. (Owner, FYI) Second WhatsApp session for the "canary" test family
The owner links the spare SIM as a second session in the gateway dashboard (QR) with an AutoReply rule and **no webhook** (its incoming
messages are our own reminders; the canary's "1" reaches us through the main session's webhook). Nothing for you on AWS. A dummy
parent "Test Papa" on that number answers its own reminder automatically every 4 hours, so the admin Monitor turns red within minutes
if anything in the chain breaks. Steps: `docs/canary.md`.

### 4. (Owner, FYI) Number changes and sessions: the lesson from 2026-11-05
The owner manages sessions and webhooks in the gateway dashboard (`https://wa.chhaon.site`). Only the server (code, build, restart,
logs) needs you.

After the switch to the new number, the new session had **no webhook**, so from about 08:00 to 20:00 IST no parent reply reached the
backend (reminders still went out). The owner fixed it by hand: a new webhook on the new session to
`https://api.chhaon.site/webhooks/whatsapp-gateway` (`message.received`, plus `message.status` if you like) with a **new secret**,
the same value set as the GitHub secret `WHATSAPP_WEBHOOK_SECRET`, then a backend deploy. The old webhook was deleted.
- Every new session needs its own webhook. Check `WHATSAPP_SENDER_SESSION_NAME` is the name of the session in use.
- The new admin Monitor (coming) shows "webhooks for replies: 0" in red when this happens.
- Login codes also go out over this number: a ban stops logins as well as reminders. Keep a spare number ready.
- Lower the ban risk: messages only to parents who agreed, low volume at first, no bursts, no links.

### 5. ~~Two Java tests fail on `main`~~ DONE (owner, 2026-11-06, commit `84a205c`)
The login code now really burns after 5 wrong guesses (the 401 no longer rolled back the failed count) and `DataApiTest` uses a real
check-in status. The full suite passes (742 tests, 0 failures, 2026-11-07).

### 6. Phone login finds accounts only by the exact number `+91…` (still open 2026-11-07; needed before real families join)
`OtpService.verify` looks a profile up by `phone` exactly. Profiles saved as `91…` (without `+`) or with no phone (old email sign-ups)
get a NEW empty account at the first phone login. Please handle it before real families join (match the old formats, or let an email
account link a phone).

## New since your last look: the pilot cap is LIVE (2026-11-07)

To protect the one WhatsApp number, at most **50 numbers** get messages and at most **5 new numbers a day** are let in; families over
the cap set everything up and wait in a line ("In line for the pilot: #N"). Details: `docs/pilot-cap.md`.
- **Database:** migration `20261107000060_pilot_cap.sql` is already applied on Sydney (`pilot_summary()` showed 9 of 50 in use).
- **Code:** pushed to `main` (commit `6061091`). `ConsentService` calls `pilot_admit` before the first message to a new number; the
  answer is `{"status":"pilot_full","position":N}` when full. If the functions are missing the backend behaves as before.
- **Line mover:** `PilotAdmissionService` runs every 10 min when the scheduler is on (`chhaon.pilot.admission-enabled`, default = the
  scheduler setting): it sends the next family's consent request and creates a `pilot_admitted` notification.
- **Sends are spaced:** the scheduler waits a random **3-8 s** between reminder and check-in sends (`chhaon.whatsapp.pace-min-ms` /
  `pace-max-ms`, default 3000 / 8000, `0` = off). With 50 parents at one time slot a tick now takes about 4-5 minutes. Keep this in
  mind for task 2 (late doses): a long tick is expected now, a 30-minute gap is not.
- **Owner's controls (SQL Editor):** `update public.pilot_settings set max_active = 60, updated_at = now();` (or `enabled = false`).
  The admin Monitor has a Pilot card with "Move to front".

## Done (thank you)

- **Google Play review account** (Yash, 2026-11-07, commit `1cae5f6`): one configured phone logs in with a fixed code, no WhatsApp sent.

- Backend Deploy is green (smoke test `HEALTH_CHECK_URL` = `https://api.chhaon.site/actuator/health`).
- Phone-OTP login, Java data API, theme picker, tablet colour and shape (migration 0042 and the 0043-0053 bundle applied on Sydney) are live.
- **Live since 2026-11-05 evening:** one WhatsApp reminder per time slot (all medicines due together in one message, one "1" confirms
  all), clearer reminder text ("दवा ले ली? जवाब में बस *1* लिखें"), the poll code (off until task 1), and an outside heartbeat.
- **Outside monitoring (Better Stack, free):** monitors on `chhaon.site`, `api.chhaon.site/actuator/health` and `wa.chhaon.site`, plus a
  scheduler heartbeat (`BETTERSTACK_HEARTBEAT_URL`, pinged after every tick). Alerts go to the owner by e-mail.

## Coming soon (heads-up)

- **Admin Monitor** (built, on the owner's local `main`): an admin-only page with health checks (scheduler, on-time sends, replies coming
  in, gateway connected + webhooks, refused webhooks, failed sends, volume), every family, activity and feedback. Order: the owner pastes
  `supabase/migrations/20261105000054_admin_monitor.sql` into the Sydney SQL editor FIRST, then pushes. It reads the gateway's session
  and webhook list with the backend's gateway API key.
- Stability sprint before marketing (see `docs/TODO.md`): canary family, weekly releases with a checklist, migration tracking, nightly
  database backup, gateway runbook.

## Answered

**Storage files from the old Mumbai project:** not copied before the move, so the old medicine photos and report files are LOST
(confirmed 2026-11-05). Only the owner's own family/test data is affected.

## Rules that still apply

- **Database (Sydney):** it has no migration history table. Apply SQL in the SQL editor, never `supabase db push`. If the database is
  ever copied again, keep the permissions (for example `pg_dump` without `--no-acl`) and run
  `supabase/maintenance/table_permissions_check.sql` afterwards.
- **Accounts:** do not use the owner's admin account (`gbirla.dev.rnd@gmail.com`) for setup or tests; it holds live data.
- **Test data:** only obviously fake phone numbers (`+91 9999x xxxxx`), never real or random-looking ones, in tests, seeds and demos.
- **Deploys:** a push that touches `api-java/**` deploys the backend to production; a push that touches `web/**` deploys the website.
  `whatsapp-gateway/**` is deployed by hand (task 1).

Known gaps on the Java side: a sender the gateway cannot turn into a phone number (`@lid` without a number) is ignored, even if it says
STOP; the gateway never retries, so a 500 from us loses that reply; caregiver WhatsApp alerts and appointment reminders are not ported
yet. Details: `docs/java-whatsapp-replies.md`.
