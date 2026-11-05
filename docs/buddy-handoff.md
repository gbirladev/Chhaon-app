# Handoff for the backend owner (AWS and WhatsApp gateway)

Updated 2026-11-05 (first written 2026-11-04). What is done, what is open for you, and what is coming. Nothing secret is in this file:
secrets are exchanged privately, never in the repository or a chat group.

## Done (thank you)

- Webhook secret set on the server and in the gateway; gateway webhook `Chhaon backend` points to
  `https://api.chhaon.site/webhooks/whatsapp-gateway` with events `message.received` and `message.status` (signed test = 200).
- Backend Deploy is green (the smoke test needs `HEALTH_CHECK_URL` = `https://api.chhaon.site/actuator/health`).
- Phone-OTP login, Java data API (phase 1 and 2) and the "Playful Blocks" theme are live.
- The old WhatsApp number was banned on 2026-11-05. A **new number** is linked to the same gateway and the scheduler runs for everyone
  (`CHHAON_SCHEDULER_ENABLED` = `true`, `CHHAON_SCHEDULER_ONLY_RECIPIENT` = `all`).

## Open tasks for you

### 1. Poll test: can parents answer with ONE TAP? (about 1 hour, no code needed) — NEW

Today parents type a reply (`1`, "le li", 👍). A WhatsApp **poll** would let them tap an answer instead. The gateway can already SEND a
poll (`whatsapp-gateway/src/app/api/messages/[sessionId]/[jid]/poll/route.ts`), but it does nothing with the votes: there is no poll
event in `src/lib/webhook.ts`, `messages.update` only forwards delivery status (`src/modules/whatsapp/store/index.ts`, around line 170),
and the socket has no `getMessage`, so Baileys cannot decrypt a vote. Before anyone builds this, please check whether a vote reaches
the gateway at all.

Steps:
1. Send one poll to your own phone (replace the placeholders; the API key goes in the `X-API-Key` header):
   ```
   curl -s -X POST "https://wa.chhaon.site/api/messages/<sessionId>/<91XXXXXXXXXX@s.whatsapp.net>/poll" \
     -H "X-API-Key: <gateway api key>" -H "Content-Type: application/json" \
     -d '{"question":"💊 Papa, time for Metformin (blue round tablet)","options":["✅ Taken","⏰ Later"],"selectableCount":1}'
   ```
2. On the phone: does the poll show with two tappable options? Tap **✅ Taken**. Then change the vote to **⏰ Later**.
3. On the gateway, look for anything that arrived at the moment of each tap:
   - the gateway server log (a `messages.upsert` with a `pollUpdateMessage`, or a `messages.update` with `pollUpdates`),
   - the session's message list in the dashboard (a new incoming row),
   - the webhook log (did `message.received` fire, and with what body?).
4. Tell the product owner: (a) did the poll arrive and look right on the phone (Android and, if possible, iPhone), (b) did any event
   arrive per tap, (c) the event body with the phone number masked.

What happens next: if a vote event arrives, the gateway change is about 100-150 lines (keep each sent poll's message and secret, add
`getMessage` to the socket, decrypt with `getAggregateVotesInPollMessage`, send a new webhook event `poll.vote
{pollKeyId, voter, selectedOptions}`), and the Java side is built by us (send reminders as polls, store the poll id, handle `poll.vote`).
Note: the poll route returns only `{status:true}`, not the message id; we would need the sent message's key id in the response.
If no event arrives at all, polls are dropped and parents keep typed answers.

Planned poll answers (one choice per poll; a changed vote counts the last one; typed STOP still works):

| Message | English | Hindi |
|---|---|---|
| Dose reminder | ✅ Taken / ⏰ Later | ✅ ले ली / ⏰ थोड़ी देर में |
| Morning check-in | 😊 I'm fine / 📞 Please call me | 😊 ठीक हूँ / 📞 फ़ोन कीजिए |
| Consent (first message) | ✅ Yes, start / ❌ No, thanks | ✅ हाँ, शुरू करें / ❌ नहीं चाहिए |

### 2. Two Java tests fail on `main`
- `DataApiTest.activityReturnsWhatWasSent` (line 108): it inserts a check-in with status `"ok"`, which the `checkin_status` type does not
  allow (pending / sent / replied / missed). Probably `"replied"` was meant.
- `OtpServiceTest`: `aWrongCodeIsRejectedAndBurnsAfterTooManyAttempts` and sometimes `requestThenVerifyLogsInAndCreatesAProfileForANewPhone`.
  Not looked into.

### 3. Phone login finds accounts only by the exact number `+91…` (low priority)
`OtpService.verify` looks a profile up by `phone` exactly. Profiles saved as `91…` (without `+`) or with no phone (old email sign-ups)
get a NEW empty account at the first phone login. Today only the owner's own family is affected (fixed by hand), so this can wait,
but please handle it before real families join (match the old formats, or let an email account link a phone).

### 4. New WhatsApp number: keep it alive
- Check that `WHATSAPP_SENDER_SESSION_NAME` is the name of the NEW session.
- Login codes also go out over this number: a ban stops logins as well as reminders. Keep a spare number ready.
- Lower the ban risk: messages only to parents who agreed (replied 1), low volume at first, no bursts, no links.

## Coming soon (heads-up)

- **Tablet colour and shape** (merged on the owner's local `main`, not pushed): the backend now reads two new columns
  `medicines.pill_colour` and `medicines.pill_shape`, and with `ddl-auto=validate` it will **refuse to start** if they are missing.
  Order: the owner pastes `supabase/migrations/20261105000042_pill_look.sql` into the Sydney SQL editor FIRST, then pushes.
- **Reply fix** (merged locally): a late plain `1` now answers the missed dose that was sent after the check-in, not the check-in.
- **Theme picker** (live since 2026-11-05): Family page, top right. The default is called "Chhaon": your Smart Gurukul violet, slightly
  softened (same hue and brightness, less saturation; `DEFAULT_SOFTEN` in `web/scripts/build-palettes.mjs`, `theme.css` untouched).
  Users can also pick Classic (the first teal), Teal, Blue, Saffron, Rose or their own colour.

## Answered

**Storage files from the old Mumbai project:** not copied before the move, so the old medicine photos and report files are LOST
(confirmed 2026-11-05). Only the owner's own family/test data is affected; the dangling links in the database are being cleaned up.

## Rules that still apply

- **Database (Sydney):** it has no migration history table. Apply SQL in the SQL editor, never `supabase db push`. If the database is
  ever copied again, keep the permissions (for example `pg_dump` without `--no-acl`) and run
  `supabase/maintenance/table_permissions_check.sql` afterwards (26 expected lines).
- **Accounts:** do not use the owner's admin account (`gbirla.dev.rnd@gmail.com`) for setup or tests; it holds live data.
- **Deploys:** a push that touches `api-java/**` deploys the backend to production; a push that touches `web/**` deploys the website.

Known gaps on the Java side: a sender the gateway cannot turn into a phone number (`@lid` without a number) is ignored, even if it says
STOP; the gateway never retries, so a 500 from us loses that reply; caregiver WhatsApp alerts and appointment reminders are not ported
yet. Details: `docs/java-whatsapp-replies.md`.
