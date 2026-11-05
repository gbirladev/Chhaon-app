# Java backend: reading the parent's WhatsApp replies

Branch `java-reply-pipeline`. The self-hosted WhatsApp gateway (Baileys, `whatsapp-gateway/`) has **no quick-reply
buttons**, so a parent answers by **typing**. This page is the contract and the rules. Tests: `docs/java-tests.md`.

## Webhook contract

| | |
|---|---|
| URL | `POST https://api.chhaon.site/webhooks/whatsapp-gateway` (nginx proxies every path to the backend on :8080) |
| Auth | none of the user kind (public in `SecurityConfig`); the **HMAC signature** is the only door and it **fails closed** |
| Header | `X-Webhook-Signature: sha256=<hex>`, hex = HMAC-SHA256 of the RAW body with the webhook secret (checked in constant time) |
| Secret | env `WHATSAPP_WEBHOOK_SECRET` (property `app.whatsapp.webhook-secret`) |
| Answers | 503 secret not configured; 401 signature missing/wrong (nothing is done); 400 not JSON; 413 too large (256 KB); **200** accepted (also for events we ignore on purpose); 500 processing failed (everything rolled back) |
| Events used | `message.received`, `message.status`. Any other event is accepted and ignored. |

Nothing from the request (numbers, text) is logged; numbers are masked like `WhatsAppSender.mask` (`***123`).

### Payload fields we rely on (read from `whatsapp-gateway/src/lib/webhook.ts`)

```
{ "event": "message.received", "sessionId": "...", "timestamp": "...",
  "data": { "key": { "id": "<message id>", "remoteJid": "919876543210@s.whatsapp.net", "fromMe": false },
            "from": "919876543210@s.whatsapp.net", "isGroup": false, "chatType": "PERSONAL",
            "type": "TEXT", "content": "1",
            "quoted": { "key": { "id": "<id of the message that was swiped/quoted>" } } | null } }

{ "event": "message.status", "data": { "keyId": "<message id>", "remoteJid": "...", "status": "PENDING|SENT|DELIVERED|READ" } }
```

* Sender = first usable phone jid of `data.from`, `data.key.remoteJid`, `data.sender`. Device suffix (`:12`) is stripped,
  `@c.us` accepted. The number becomes E.164 (`+919876543210`) and is matched to `care_recipients.whatsapp_phone`.
* Only 1:1 chats: `isGroup`, `chatType != PERSONAL`, `@g.us`, `@newsletter`, `status@broadcast` are ignored. `key.fromMe` is ignored.
* Only `type == TEXT` with non-empty `content`. Photos, voice notes, stickers (even with a caption) are ignored.
* Quote: `data.quoted.key.id` (the gateway copies Baileys `contextInfo.stanzaId` there).
* Idempotency key: `data.key.id`, stored as `gw:in:<id>` in `whatsapp_events` **inside the same transaction** as the work.

### Unknowns and limits (honest list)

* **LID senders.** WhatsApp sometimes addresses a chat as `1234...@lid`. The gateway tries to turn it into a phone jid
  (Baileys `remoteJidAlt`, then its contact table) and puts the result in `data.from`. If it could not, `from` stays `...@lid`
  and the payload has **no phone field at all**. We then cannot know who wrote: the message is ignored (200,
  `ignored:no-phone`, a WARN with a non-reversible tag). **This includes a STOP from such a parent: it is NOT honoured.**
  Nobody can fix this on our side; watch the logs for `sender has no phone number` and, if it happens, ask in the gateway
  project how to get the LID to phone mapping (or the parent must reply from a chat that resolves).
* **The gateway does not retry.** `sendWebhookRequest` fires once (10 s timeout) and only logs a failure. A 500 from us means
  that reply is lost (the claim is rolled back, so a manual re-post would work). Keep the backend up; do not deploy during the morning window.
* **Delivery receipts.** `message.status` carries `keyId` (the same id our send returned) but only PENDING/SENT/DELIVERED/READ.
  The gateway maps Baileys status 0 (error) to PENDING, so a **failed** delivery is not visible. Nothing is stored for
  delivered/read (the Python code only used them for the "love" feature). A `FAILED`/`ERROR` status, if a future gateway sends it,
  tells the family once (`whatsapp_failed`), like Python's `_handle_failed`.
* Button-less behaviour of real phones (swipe-reply payload, exact `quoted` shape) was read from the code, **not observed live**.
  Test once with a real phone before relying on the quote path.

## What a parent can type

Convention in every message we send: **1 = yes / taken / I am okay, 2 = no / call me, STOP any time.**
Capitals, full-width letters, spaces, zero-width characters, emoji and punctuation do not matter.

| Meaning | English | Hindi / Hinglish |
|---|---|---|
| Yes / taken / okay | `1`, `yes`, `taken`, `done`, ✅, 👍 (typed, or a 👍 reaction on the message: see below) | `1`, `१`, `हाँ`, `haan`, `ले ली`, `le li`, `liya`, `ली` |
| Everything taken | `all taken` | `sab le li`, `सब ले ली` |
| Check-in: I am okay | `I am okay`, `fine`, `all good` | `theek hoon`, `ठीक हूँ`, `सब ठीक है` |
| No / not yet / call me | `2`, `no`, `not yet`, `call me`, `help`, `not well`, ❌ | `2`, `nahi`, `नहीं`, `phone karo`, `फोन करो`, `तबीयत खराब` |
| Stop | `STOP`, `unsubscribe` (max 4 words, no negation) | `रुको`, `रुकिए`, `बंद करो` |
| Start again | `START` | `शुरू` |

* A bare `ok` / `theek hai` is only an **acknowledgement**: it answers a consent question and a check-in, but **never counts as "taken"**.
* STOP rules are a faithful port of the Python code: at most 4 words, no `don't / not / no / never / मत / नहीं`.
  Known limit: "please stop sending me messages" (5 words) is not an opt-out. "don't start" never grants consent.
* A reply of 5 or more words that merely contains a keyword is not an answer ("I took my walk and then ok").

## What a reply answers (routing priority)

1. **STOP** always wins (`START` too). STOP belongs to the **number** (`phone_opt_outs`): it is recorded even if no parent carries
   that number yet, stops every parent sharing it, and outlives any parent record.
2. An open **consent question** for this number (`consent_requested_at` set, no consent, not opted out; if several parents share the
   number, the most recently asked one): 1 / yes / ok / START agree, 2 / no declines (and records the number as opted out).
3. The message **quotes** one of our messages: its id equals `dose_events.whatsapp_message_id` (or `checkins.whatsapp_message_id`,
   migration 0041). That dose / check-in is answered, even if it was already marked missed (late taps become `taken_late`).
4. Otherwise the **most recent dose reminder still `sent`** (12 h at most). `1`/taken confirms that ONE dose and uses stock once
   (`consume_stock`); `all taken` confirms the whole time slot (`confirm_dose_slot`); `2` replies "reply 1 once you have taken it".
5. Otherwise **today's check-in** still `sent` (or `missed` within 12 h): 1 / okay = replied; 2 / call me / "not well" = replied **and**
   the family gets `checkin_call_requested` (once).
6. Otherwise a dose marked `missed` in the last 6 h: `taken_late`.
7. Otherwise unknown: one short help text, **at most once per 6 hours per number** (so a chat can never loop). "Thanks" gets no answer.

Never answered at all: numbers that are not a parent, numbers that said STOP (except START), parents who never consented (and were not asked).
Only the registered number can answer for a parent. Two collisions to know: a dose and the check-in are often due at the same
time, so a `1` goes to the dose first and the confirmation reminds the parent "Are you okay today? Reply 1 ..." (a second `1` answers the
check-in); and `help` with no check-in open gets the help text, the family is not alerted.

## Messages we send (all end with how to reply)

`MessageTexts` (reminder, check-in, consent request) and `ReplyTexts` (answers), English and Devanagari Hindi. Example:
`Namaste Papa 🙏 It's time for your medicine (Metformin). Reply 1 when you have taken it.` /
`Good morning Papa 🙏 Are you okay today? Reply 1 if you are okay, 2 if you want a call.` /
`Namaste Papa 🙏 Ananya would like to send you gentle medicine reminders on WhatsApp. Reply 1 to start, 2 for no. You can reply STOP any time.`
**The Hindi wording needs a native review.**

## Routes the web app calls

* `GET /features` (public) returns `{"escalation":false,"love":false}`.
* `POST /recipients/{id}/consent-request` (login; caller must be in the parent's circle, else 404) returns `{"status": sent | already_consented |
  opted_out | too_soon | too_many | no_phone [, "retry_after": seconds]}`; 502 if WhatsApp is unavailable. Limits come from the database function
  `claim_consent_request`: one request per number per 10 minutes (`chhaon.consent.resend-minutes`) across all families, 10 per caregiver per day
  (`chhaon.consent.requests-per-day`). A failed send does not count.

## Reply with a 👍 reaction (F6, branch `f6-thumbs-reaction`)

An older parent (or helper) can long-press our message and tap 👍. That counts exactly like typing `1` **for that message**.

**Gateway (needs a gateway deploy, see `docs/buddy-handoff.md`).** Before this change the gateway dropped reactions:
`processAndSaveMessage` listed `reactionMessage` among the "technical" types and returned before any webhook
(`whatsapp-gateway/src/modules/whatsapp/store/index.ts`, the `ignoredTypes` list), and `extractMessageContent` in
`src/lib/webhook.ts` has no reaction case (it would have been `type TEXT`, empty content, no reacted key). Now
`src/lib/reaction.ts` (`extractReaction`, `reactionPayload`, tests in `reaction.test.ts`, `npm test`) and
`onReactionReceived` in `webhook.ts` send, for a NEW reaction from someone else (never history sync, never our own):

```
{ "event": "message.received", "data": { "key": { "id": "<reaction id>", "remoteJid": "<sender>", "fromMe": false },
  "from": "...", "sender": "...", "isGroup": false, "chatType": "PERSONAL", "type": "REACTION", "content": "👍",
  "quoted": null,
  "reaction": { "text": "👍", "removed": false, "key": { "id": "<id of the message reacted to>", "remoteJid": "...", "fromMe": true } } } }
```

Removing a reaction sends the same with `text: ""`, `removed: true`. Reactions are not stored in the gateway's message table.
An old backend ignores these (`type != TEXT`), and the new backend works with the old gateway (it simply never gets one), so
the two can be deployed in any order.

**Backend** (`GatewayEventService.reaction`, `ReplyRouter.routeReaction`):

* Counts as yes: exactly the emoji a typed reply already reads as yes: 👍 (any skin tone), ✅, ✔️, ☑️, 🆗
  (`ReplyRouter.reactionMeansYes`, reuses `ReplyClassifier`).
* Ignored on purpose: ❤️ (affection, put on any family message), 🙏 (thanks / namaste; typed 🙏 is also only "thanks"),
  😂 😮 😢, 👎 / ❌ (never "not taken" or "call me" either: a reaction cannot ask for help), and a **removed** reaction
  (it never undoes a "taken").
* What it answers: only the reacted message id, never "the latest open question":
  1. a consent request still open for that number (the request's message id is remembered at send time as a
     `whatsapp_events` row `gw:out:consent:<recipient id>:<message id>`, no migration) -> agree;
  2. a dose reminder (`dose_events.whatsapp_message_id`) sent to that number in the last 12 h, still `sent` / `missed` ->
     `taken` / `taken_late`, stock used once (the same `ReplyHandlers` path as a typed `1`);
  3. a check-in (`checkins.whatsapp_message_id`) sent in the last 12 h, still `sent` / `missed` -> "I am fine".
  Anything else (unknown, older than 12 h, already answered, another number, a number that said STOP) is ignored with
  **no reply and no help text**.
* Idempotent: the reaction's own id is claimed (`gw:in:<id>`) in the same transaction; a 👍 after a typed `1` (or a second 👍)
  finds the dose already taken and does nothing (no second stock use, no second thank-you).
* A shared number (one helper for Papa and Mummy) is no problem: the reacted id says whose reminder it was.
* Not covered: the caregiver's "turn on alerts?" question (its message id is not remembered; typing 1 works), consent
  requests sent before this release (typing 1 works), LID-only senders (as for typed replies).

## WhatsApp alerts to the family (branch `family-alerts`)

**Sign-up.** `POST /alerts/whatsapp/request` (login), body `{"phone": "+91..."}` (E.164, else 422). Answers `{"status": sent | opted_out |
too_soon | too_many [, "retry_after"]}`, 502 if WhatsApp is down (nothing recorded, the attempt does not count). Same limits as the parent
consent request (`claim_consent_request`). It sends `MessageTexts.alertOptIn` to that number and then sets `profiles.alert_phone`,
`alert_requested_at`, clears `alert_confirmed_at` / `alert_consent_at` (a new number is a new person). Turning off in the app is the
existing `PATCH /me {alertsOff: true}`.

**The caregiver's answer** (ReplyRouter `AlertOptIn`, checked right after STOP/START): `1` / yes / ok / 👍 turns alerts on
(`alert_consent_at`, `alert_confirmed_at`); `2` / no closes the question; for a number that is nobody's parent number it is also put in
`phone_opt_outs` (never asked again until it types START, which then answers "you can turn alerts on again in the app"). STOP turns
alerts off (and closes an unanswered question); START restores alerts that were once confirmed.

**One number for a caregiver AND a parent:** the reply answers the question we asked LAST. The alert question wins only when it was sent
after every question still open for the parent on that number (a pending consent request, a dose reminder or check-in sent in the last
12 h still waiting or marked missed). A swipe-reply quoting a reminder or check-in always answers that message. `2` to the alert question
on a parent's number never opts the number out (the parent's reminders go on). The "alerts on" reply adds "Reply 1 again for the next
medicine" when a reminder is still waiting on that number.

**Delivery.** The `notifications` table is the outbox (`whatsapp_sent_at`, `whatsapp_skipped_at`, `whatsapp_attempts`, migration
20261011000015, no new migration). `AlertDelivery` runs as the LAST step of every tick (and right after a webhook reply, only while the
scheduler is on, so "call me" goes out at once). Types: `dose_missed` (several medicines due together = one alert with a count, like
Python), `checkin_missed`, `checkin_call_requested`, `refill_needed`, `out_of_stock` (database trigger), `reminder_not_sent`,
`parent_stopped`, `parent_declined`. One WhatsApp text per caregiver of the circle whose own number confirmed; everyone else's copy is
marked skipped. Claimed before sending (no duplicates), a failed send is retried up to 3 times then skipped, never sent when older than
6 h, at most 6 per caregiver per hour (`chhaon.alerts.max-attempts`, `.lookback-hours`, `.per-hour`). The "Me" profile gets no alerts
about itself except low stock / out of stock. No quiet hours (Python had none). The in-app notification is never lost: only the three
WhatsApp columns change. Texts: `AlertTexts` (EN/HI, no links or numbers, end with "Reply STOP to turn off these alerts.").

## Environment and registration

| Variable | Meaning |
|---|---|
| `WHATSAPP_WEBHOOK_SECRET` | HMAC secret (generate: `openssl rand -hex 32`). Blank = endpoint answers 503 to everything |
| `WHATSAPP_GATEWAY_URL`, `WHATSAPP_GATEWAY_TOKEN`, `WHATSAPP_SENDER_SESSION_NAME` | sending (blank URL = dry-run, nothing is delivered) |
| `CHHAON_SCHEDULER_ENABLED` | stays **false/unset** until the checklist in `docs/TODO.md` is done |

Register in the gateway dashboard (Webhooks page): URL `https://api.chhaon.site/webhooks/whatsapp-gateway`, the same secret, events
`message.received` and `message.status` (not `*`). Use its "Test" button: the signed `test` event must answer 200.

## Database changes

`supabase/migrations/20261104000041_checkin_message_id.sql` adds `checkins.whatsapp_message_id` (+ index). Optional: the code stores the id
best-effort and only looks up check-in quotes when the column exists. `20261104000040_scheduler_heartbeat.sql` (from the coordinator, copied unchanged) is
written by every tick. Neither was applied anywhere except the throwaway test database.

## Changes to the existing tick (besides the texts)

* Heartbeat after every tick (best effort, never changes counters).
* Check-in message id stored (best effort).
* **Bug fix found by the tests:** the check-in step ignored `phone_opt_outs`, so a number that said STOP could still get a daily check-in when another
  parent record carried it. It now checks like the dose step.

## Not ported yet

Appointment reminders; escalation (Safety net);
love loop; slot grouping in the reminder itself (one message per medicine is sent; "all taken" confirms the slot); `/cron/tick`-style secret endpoint
(the admin tick exists); delivery-failure detection; `parent_responded` after a family alert; distress words when no check-in is open;
photo of the medicine in reminders (text only).
