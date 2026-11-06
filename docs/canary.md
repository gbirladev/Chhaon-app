# Canary: a test family that proves reminders and replies work

**Why.** On 2026-11-05 parents' replies were silently lost for 12 hours and nobody noticed. The canary is a pretend family whose
"parent" is a spare phone number that answers its own reminder automatically. Every 4 hours it goes through the whole chain:

```
reminder job -> WhatsApp gateway (our number) -> spare phone -> its auto-reply "1" -> gateway -> webhook -> our server marks "taken"
```

The Monitor's **Canary** card is green when the last test reminder was answered within 10 minutes. If anything in the chain
breaks, it turns red at the next 4-hourly slot (within 10 minutes of it), and an entry appears under "Open problems".

| What | Value |
|---|---|
| Caregiver | "Chhaon Canary" (dummy account: no password, cannot log in, gets no alerts) |
| Person | "Test Papa", English, India time, consent already given, no daily check-in |
| Medicine | "Test tablet", 1 tablet, no stock counting, no photo |
| Times | 02:00, 06:00, 10:00, 14:00, 18:00, 22:00 India time (6 reminders a day) |
| Reminder | Always the plain **text** reminder (never a poll, never a photo), because an auto-reply cannot vote in a poll and reads only text |
| Left out of | pilot stats, the day's totals and activity in the Monitor, weekly summaries, every family alert (a missed test dose alerts nobody) |
| Shown | its own Canary card; in Families as the last row with the grey "Test family" badge; in "Coming up" |

The message the spare phone gets every 4 hours:

```
Namaste Test Papa 🙏
Medicine time: *Test tablet*
1 tablet

Taken it? Just reply *1*
```

## Setting it up (owner, about 20 minutes; no AWS needed)

**Order for production:** the migration SQL first, then push (deploy the backend), then the setup SQL (step 5).

**1. A spare SIM.** Any Indian prepaid SIM that is not used by anyone in the app. Put it in any phone (an old Android is fine),
install WhatsApp, register the number. Keep that phone switched on, charging, and on Wi-Fi: if it is off for a long time the
gateway session logs out and the canary goes red (which is correct: it tells you).

**2. Link it to the gateway as a second session.** Gateway dashboard (`https://wa.chhaon.site`) -> **Sessions** -> new session,
name it `Canary` -> **Start** -> a QR code appears. On the spare phone: WhatsApp -> Settings -> **Linked devices** -> Link a device
-> scan the QR. The session shows CONNECTED. Do not touch the main session.

**3. Add the auto-reply rule on the Canary session.** Dashboard -> **Auto Reply** -> make sure the **Canary** session is selected
(not the main one) -> new rule:

| Field | Value |
|---|---|
| Keyword | `Medicine time` |
| Match Type | **Contains** |
| Select Trigger Audience | **Private Chats Only** |
| Reply Message | `1` |
| Media URL | leave empty |

Also check **Bot Settings** for the Canary session: the bot is **enabled** and the auto-reply access is **Public (Everyone)**
(these are the defaults for a new session).

Why `Medicine time` and not `Test tablet`: our thank-you answer ("Thank you 🙏 Noted: Test tablet ✅") also contains
`Test tablet`, so the phone would answer the thank-you with another `1`. That second `1` could turn an earlier missed test dose
into "taken late" and hide a real problem. `Medicine time` appears only in the reminder itself. (If the reminder wording ever
changes, the canary goes red and the keyword must follow the new wording; the tests check the keyword is still in the reminder.)

**4. Do NOT add a webhook to the Canary session.** Only the main session needs its webhook (to
`https://api.chhaon.site/webhooks/whatsapp-gateway`). The Canary session receives OUR reminders; if it had a webhook, our server
would receive our own reminders as "incoming messages". Our server treats every webhook the same way (it does not look at which
session sent it, only at the sender's number), so those would count as "replies coming in" and could hide exactly the outage the
canary is meant to catch. The canary's "1" reaches us through the **main** session's webhook, like a real parent's reply.

**5. Create the family in the database.** Supabase (Sydney) -> SQL Editor:
1. First time only: paste `supabase/migrations/20261106000055_canary.sql` and Run (safe to run again).
2. Deploy the backend (push to `main` -> Backend Deploy).
3. Open `supabase/maintenance/canary_setup.sql`, replace `+9199999xxxxx` with the spare SIM's number (with `+91`, no spaces),
   paste it into the SQL Editor and Run. The last table shows Test Papa with the 6 times. Running it again is safe; running it with
   another number moves the canary to that number.

The setup refuses a number that already belongs to a real person or caregiver, or that said STOP.

**6. Watch the first one.** Monitor -> Health. The Canary card says "waiting for the first test reminder" (grey, no alert) until
the next of 02:00 / 06:00 / 10:00 / 14:00 / 18:00 / 22:00 India time. At that time the spare phone gets the reminder, answers `1` a
second later, gets "Thank you 🙏 Noted: Test tablet ✅", and within 5 minutes the card is green with the round trip in seconds.
If it is red instead: open the spare phone. Did the reminder arrive? Did it answer `1`? (If not: the rule, step 3.) Did the `1`
arrive but the card stays red? Then replies are not reaching us: that is exactly what the canary is for (Replies coming in /
WhatsApp gateway / Webhook calls cards).

## What the card means

| Card | Meaning | What to do |
|---|---|---|
| Green | The last test reminder was answered within 10 minutes. Shows when it was sent, when it was answered, the round trip, and "x of y answered in time" for 24 hours | Nothing |
| Amber "slow" | Answered, but after more than 10 minutes | The spare phone was offline for a while, or the gateway was slow. Watch the next slot |
| Red "no answer" | The last test reminder got no answer within 10 minutes (or was marked missed) | Check the other cards. Then the spare phone: on, internet, Canary session connected, rule present? |
| Red "not sent" | No test reminder went out for 5 hours | The reminder job or the send is broken (Reminder job / WhatsApp gateway cards, backend log) |
| Grey | No canary set up, or set up less than 5 hours ago and nothing sent yet | Nothing (never an alert) |

The 5-minute health checker records red / amber in "Open problems" like every other check and closes it when the card is green again.

## Removing it

Paste `supabase/maintenance/canary_remove.sql` into the SQL Editor and Run. It deletes the caregiver, Test Papa, the medicine, the
times and every test dose. The card goes grey ("not set up"). Then delete the Canary session in the gateway dashboard.

## Things to know

* The canary's 6 reminders (and 6 thank-yous) a day are real messages from our number. They count in the Monitor's message volume
  (ban risk is about all messages) and are also shown on their own ("Test messages today").
* The canary's replies are counted in "Replies coming in": they really do prove the webhook works.
* WhatsApp sometimes reports a sender only by a "LID" instead of the phone number; such a reply cannot be matched to anyone
  (docs/java-whatsapp-replies.md). If that happens to the canary, it goes red: a real signal that parents' replies may be lost too.
* The Monitor shows the spare SIM's number masked like every other number (`+91 ••••• •1234`, docs/admin-monitor.md); the full
  number is only in the database and in `canary_setup.sql` when you run it.
* The canary is never paused by a plan (its dummy caregiver is on the Family plan) and never gets a check-in or a low-stock alert.

Code: migration `supabase/migrations/20261106000055_canary.sql` (flags, `canary_setup` / `canary_remove`, notification filter,
pilot stats); `AdminHealthService.canary()`; `SchedulerService.sendSlot` (text only); tests `CanaryTest.java`,
`supabase/tests/canary.test.sql`.
