# Weekly summary to the family (F3)

Branch `f3-weekly-summary`. Every Sunday morning each caregiver with WhatsApp alerts on gets ONE short message about everyone they
care for, and the Family tab shows the same numbers as a "This week" card.

## The message

One message per caregiver per week. With one person:

```
Chhaon weekly 🗓️ · Mummy
30 Oct – 5 Nov
Medicines confirmed: 26 of 28
Missed: Metformin Tue night, Thu night
Given by: Sunita 24 · you 2
Refill soon: Metformin (6 left)
Next visit: Dr Mehta, Tue 10 Nov, 11:00 AM

Full details: chhaon.site/app
Reply STOP to turn off these alerts.
```

With several people: a `Chhaon weekly 🗓️` header, the period once (or per person when their time zones give different weeks), then
a `*Name*` block per person (at most 5, then "+N more in the app."). A combined message was chosen over one per person: at most one
message a week per caregiver, and a caregiver of three people is not sent three messages in a row.

Lines, each only when there is something to say:
* **Medicines confirmed**: taken + taken_late out of (taken + taken_late + missed), from `dose_events` over the 7 full days before
  today on the PERSON's clock. pending / sent / skipped are not counted.
* **Missed**: medicine, weekday and part of the day (person's clock), at most 4, then "(+N more)".
* **Given by**: shown only when someone other than the person gave a dose. Per confirmed dose: `confirmed_by` (a family member who
  marked it in the app: "you" or their first name) when that column exists; a non-WhatsApp `confirm_source` without a name counts as
  "family"; otherwise whoever gets the reminders (helper's name, "you" / the family member who chose "Me", or the person). Both columns
  come from another branch and are read through `to_jsonb(de)`, so the code works with or without them.
* **Check-ins answered** (replied out of replied + missed), **Refill soon** (active, stock ≤ refill level, at most 3), **Next visit**
  (next appointment within 60 days, with "(their time)" when the zones differ), **Week N** for a pregnancy, **Next vaccine** for a
  child (`child_vaccines`, earliest not given, due today or later).
* English or Hindi by the CAREGIVER's language. No phone numbers or payments; the only link is the short `chhaon.site/app` in the footer.

## When and to whom

* Sunday, from 9:00 until 13:00 on the CAREGIVER's own clock (`chhaon.weekly.from-hour` / `until-hour`), checked every 5 minutes
  (`chhaon.weekly.tick-interval-ms`).
* Only caregivers with `profiles.alert_consent_at` and an `alert_phone` that is not in `phone_opt_outs`.
* Never about the caregiver's own "Me" profile, never to the person or their helper (a caregiver whose alert number is the person's /
  helper's reminder number gets nothing about that person). A person with no counted dose and no check-in that week is left out;
  nobody left = no message.
* `weekly_summary_sends (user_id, week_of)` (week_of = caregiver's local Sunday) is claimed before sending: at most one per caregiver
  per week, across restarts and overlapping runs. A WhatsApp failure is retried at the next run, at most 3 tries in all
  (`chhaon.weekly.max-attempts`). A run that died between claim and send is not retried (better none than two).
* Own timer (`WeeklySummary.scheduledRun`), not inside `SchedulerService.tick`: it cannot break the reminders. Same switches:
  `chhaon.scheduler.enabled` and `chhaon.scheduler.only-recipient` (one id = only that person and their family; unreadable = nothing).

## Deploy order

1. Paste `supabase/migrations/20261105000048_weekly_summary.sql` into the Sydney SQL editor (idempotent), then run
   `supabase/maintenance/restore_table_permissions.sql` (or check with `table_permissions_check.sql`).
2. Deploy the backend. Without the table the timer only logs a warning per run and sends nothing.
3. Deploy the web app (the card hides itself when the endpoint fails).

## In the app

`GET /api/v1/recipients/{id}/weekly-summary` (family circle only, otherwise 404) returns the person's week as above (the viewer is "you").
The Family tab shows it as "This week" (`web/src/screens/WeeklySummary.tsx`), not on the "Me" profile.

## Known limits

* "Given by" without `confirmed_by` uses TODAY's receiver: after a change of receiver, older doses are counted for the new one.
* The card covers the 7 full days before today, so mid-week it is "the last 7 days", not "Monday to today".
* Hindi needs a native read: `docs/hindi-review.md` Part 10.

Code: `api-java/.../scheduler/WeeklySummary.java`, `WeeklySummaryController.java`, `whatsapp/WeeklySummaryTexts.java`; tests
`WeeklySummaryTest`, `WeeklySummaryTextsTest`, `supabase/tests/weekly_summary.test.sql`.
