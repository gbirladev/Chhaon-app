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

### 1. Deploy the gateway change "poll answers" (about 45 minutes) — NEW, replaces the poll test

Parents will answer a reminder by tapping a WhatsApp **poll** option instead of typing. This gateway change (`whatsapp-gateway/`, which
our pipeline does NOT deploy) does three things:
- the poll route now answers with the sent poll's id: `{"status":true, "messageId":"3EB0...", "data":{"key":{"id":"3EB0...", ...}}}`;
- every poll the gateway sends is kept for 30 days in a new table `SentPoll` (a vote can only be read with the poll's secret, so this
  must survive a restart), and the socket gets a `getMessage` that returns it;
- a vote is decrypted and sent to the webhook as a NEW event `poll.vote`. It goes to every webhook that has `poll.vote` OR
  `message.received` ticked, so the existing `Chhaon backend` webhook needs no change.

Our Baileys version (7.0.0-rc.9) does not decrypt votes itself (that code is switched off inside Baileys), so the gateway does it:
it tries the phone and the LID form of both numbers, because WhatsApp uses either depending on the chat.

Files: `src/lib/poll.ts`, `src/lib/poll-decrypt.ts`, `src/lib/poll.test.ts`, `src/modules/whatsapp/poll-votes.ts` (all new),
`src/lib/webhook.ts` (`poll.vote` event, `onPollVote`), `src/modules/whatsapp/store/index.ts` (votes and sent polls),
`src/modules/whatsapp/instance.ts` (`getMessage`), the poll route, `prisma/schema.prisma` (model `SentPoll`), `package.json`
(`npm test` runs the poll tests too), and the event lists (dashboard, swagger, API docs). It includes the 👍 reaction change (1b).

The webhook body for one tap (example number):
```json
{"event":"poll.vote","sessionId":"<session>","timestamp":"2026-11-05T08:00:00.000Z",
 "data":{"pollKeyId":"3EB0POLL0000000001","from":"919999900001@s.whatsapp.net","remoteJid":"919999900001@s.whatsapp.net",
         "selectedOptions":["✅ Taken"],"selectedIndexes":[0],"timestamp":1793779200}}
```
A changed vote sends the new choice; a removed vote sends empty lists. Votes on polls the gateway did not send (or older than 30 days)
and votes in groups are ignored.

Steps:
1. On the gateway server, update the code to this branch's `whatsapp-gateway/` (the same way you installed it).
2. `npm install` (only if `node_modules` is missing), then **`npm run db:push`** (adds the new table `SentPoll`; it changes nothing
   else; with Docker the container already runs `prisma db push` at start).
3. `npm test` (18 tests, no database needed) and `npm run build`.
4. Restart the gateway process (pm2 / docker, as you run it). The WhatsApp session stays linked (auth is on disk).
5. Real test: send a poll to your own phone (replace the placeholders; the API key goes in the `X-API-Key` header):
   ```
   curl -s -X POST "https://wa.chhaon.site/api/messages/<sessionId>/<91XXXXXXXXXX@s.whatsapp.net>/poll" \
     -H "X-API-Key: <gateway api key>" -H "Content-Type: application/json" \
     -d '{"question":"💊 Papa, time for Metformin (blue round tablet)","options":["✅ Taken","⏰ Later"],"selectableCount":1}'
   ```
   The answer must contain a `messageId`.
6. On the phone, tap **✅ Taken**. In the dashboard webhook log a `poll.vote` appears with `"pollKeyId"` = that `messageId` and
   `"selectedOptions":["✅ Taken"]`. Change the vote to **⏰ Later**: a second `poll.vote` with `["⏰ Later"]` and `[1]`. Remove the
   vote: `[]`. (Until the Java side handles `poll.vote` our backend may answer it with an error or ignore it; that is fine for now.)
7. Do it once from an Android phone and once from an iPhone.
8. Tell the product owner: did each tap give a `poll.vote` with the right option (Android / iPhone), and if not, the gateway log
   lines with `[Poll]` (for example "Could not decrypt a vote on our poll ..."), with the phone number masked.

Planned poll answers (one choice per poll; a changed vote counts the last one; typed STOP still works):

| Message | English | Hindi |
|---|---|---|
| Dose reminder | ✅ Taken / ⏰ Later | ✅ ले ली / ⏰ थोड़ी देर में |
| Morning check-in | 😊 I'm fine / 📞 Please call me | 😊 ठीक हूँ / 📞 फ़ोन कीजिए |
| Consent (first message) | ✅ Yes, start / ❌ No, thanks | ✅ हाँ, शुरू करें / ❌ नहीं चाहिए |

### 1b. Deploy the gateway change "👍 reaction" (about 20 minutes) — NEW

Branch `f6-thumbs-reaction` changes the gateway (`whatsapp-gateway/`), which our pipeline does NOT deploy. Until you deploy it, a
👍 reaction simply does nothing (typed replies keep working; the backend and the gateway can be deployed in any order).
Files: `src/lib/reaction.ts` (new), `src/lib/reaction.test.ts` (new), `src/lib/webhook.ts` (`onReactionReceived`),
`src/modules/whatsapp/store/index.ts` (reactions are forwarded instead of dropped), `package.json` (`"test"` script).
No database (Prisma) change, no new webhook event: it is `message.received` with `type: "REACTION"`, so the existing webhook
subscription is enough.

Steps:
1. On the gateway server, update the code to this branch's `whatsapp-gateway/` (the same way you installed it).
2. `npm install` (only if `node_modules` is missing), then `npm test` (5 tests, no database needed) and `npm run build`.
3. Restart the gateway process (pm2 / docker, as you run it). The WhatsApp session stays linked (auth is on disk).
4. Check: from your own phone (registered as a test parent with an open reminder), long-press the reminder and tap 👍. The
   gateway webhook log shows a `message.received` with `"type":"REACTION"` and our backend answers `handled:REACTION`; the app
   shows the dose as taken and you get "Thank you 🙏 Noted: ...". Tap ❤️ on another message: nothing happens.
5. Tell the product owner whether it worked on Android and iPhone (and the masked webhook body if it did not).

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
