# Sample reminder ("Send a sample reminder to Papa now")

After sign-up the first real reminder often comes 10-14 hours later, so the family never sees the product work on day one. Today
shows a small card that sends ONE sample reminder right away. No migration, no new setting.

## Endpoint (Java, `SampleReminderController` / `SampleReminderService`)

Family circle members only (404 otherwise). No phone numbers in the answers.

* `GET /api/v1/recipients/{id}/sample-reminder` -> `{reason, sentToday, realReminderSent}`; reason = `ok | no_number |
  not_consented | opted_out | no_medicines | limited`.
* `POST /api/v1/recipients/{id}/sample-reminder` -> `200 {status: sent, slotTime, at}`, `409 {status: no_number | not_consented |
  opted_out | no_medicines}`, `429 {status: limited}` (one per person per day, the person's local day), `429 {status: busy}` (global
  guard, `chhaon.sample-reminder.global-per-hour`, default 60), `502 {status: send_failed}` (the day is not used up).

## What is sent

To whoever gets the person's reminders (person, "Me" or a helper), in their language: the first line
"🔔 Sample reminder from Chhaon (so you know what it looks like)" / "🔔 छाँव का नमूना रिमाइंडर (ताकि आप देख लें कि यह कैसा दिखेगा)",
then the normal reminder of the person's NEXT time slot, built by the same code as the real one (`SchedulerService#slotText` /
`#slotPoll`). A poll when `WHATSAPP_POLLS_ENABLED` is on (text for the canary family or when the poll fails), no photo.

**No dose_events row is created or changed**, so nothing counts in Today, stats or adherence. Remembered only in `whatsapp_events`:
`gw:sample:<recipient>:<local date>` (daily limit; its time = when it went out) and `gw:out:sample:<recipient>:<message id>`.

## A tap on the sample

Never a dose. A poll vote (either option), a 👍 reaction or a swipe-reply on the sample, or a typed 1 / yes / no within 3 hours when
the sample is the LAST thing sent to that number (a real reminder or check-in sent after it wins), is answered once (per person, per
3 hours) with: "👍 That was only a sample, so no medicine was marked as taken. The real reminders start at 8:00 pm 🙏" /
"👍 यह सिर्फ़ एक नमूना था, इसलिए कोई दवा दर्ज नहीं हुई। असली रिमाइंडर रात 8:00 से आएँगे 🙏" ("tomorrow" / "कल" when it is
tomorrow). A swipe-reply quoting a real reminder still confirms that reminder.

## App

`web/src/screens/SampleReminder.tsx`, under the consent banner on Today: shown when the receiver agreed, there is an active medicine,
no sample was sent today and no real reminder has been sent yet. States: send, sending, sent, limited, busy, error (en + hi).

Tests: `SampleReminderTest` (DB), `SampleReminderTextsTest`.
