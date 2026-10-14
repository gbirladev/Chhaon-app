# Pilot stats (migration 0028)

A page for the owner of the app: Family tab > **Pilot stats**. Totals only. No names, phone numbers or emails are ever returned.

## Who can see it
Only an account with `profiles.is_admin = true`. Nobody can set it for themselves (guarded in the database). The link only shows for admins, and the data function refuses everyone else.

Make someone an admin (Supabase > SQL Editor, or `npx supabase@2.120.0 db query --linked "..."`):
```sql
update public.profiles set is_admin = true where email = 'you@example.com';
```

## What it shows
- Caregivers (total, new this week, **active this week**), parents who agreed / waiting / stopped, relatives who joined.
- **Still active after 4 weeks** (of the caregivers who joined 4+ weeks ago).
- Do the reminders work, last 7 days: **% of reminders confirmed** (Taken), **% of check-ins answered**.
- A column chart of reminders confirmed per day (14 days, India time) with a table view.
- Plans: on trial, trial ending within 7 days, trial over (on Free), on a paid plan, medicines/parents paused by plan.
- WhatsApp messages sent in 7 days and an **upper estimate** of Meta's fee (Rs 0.115 per Utility, Rs 0.86 per Marketing, GST extra; the constants are at the top of `web/src/screens/Stats.tsx`). Reminders sent while the parent's 24-hour window is open are free, so the real figure is lower. Check Meta's rate card.

## How "active" is measured
The later of the caregiver's last login and the last time their phone refreshed its session (`auth.sessions.refreshed_at`), so people who stay logged in still count. It is a proxy: opening the app is what refreshes the session, but a refresh can lag.

## Not measured
"Willing to pay" (ask the families), page-by-page usage, delivery failures. Tests: `supabase/tests/admin_stats.test.sql`.
