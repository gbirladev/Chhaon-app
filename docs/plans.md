# Pricing plans and the free trial (migration 0027)

Four plans. The limits live in the `plans` table, so they can be changed without a release.

| Plan | Parents per caregiver | People in a family circle (owner included) | Medicines per parent |
|---|---|---|---|
| `free` | 1 | 1 (just you, no invites) | 2 |
| `plus` | 1 | 5 | 10 |
| `family` | 4 | 10 | 20 |
| `ultra` | unlimited | unlimited | unlimited |

"Unlimited" (a NULL limit) still has a hidden **system ceiling** so a runaway account cannot cost us unbounded money: 20 parents, 50 people in a circle, 100 medicines per parent. The Plans screen shows "Unlimited".

## The trial

Every account starts with a **30-day trial** (`profiles.trial_ends_at`). While it runs, a `free` account gets the **Plus** limits.
When it ends the account is on Free again. Nothing is deleted: what was added stays, but nothing new can be added.
Existing accounts got 30 days from the day the migration ran. Paid plans (`plus`, `family`) ignore the trial date.
A caregiver cannot change their own plan or trial date; it is done by hand.

A banner on the Today screen shows in the last 7 days of the trial and after it ended. The Plans screen shows the details.

## Switch a person's plan or trial (Supabase > SQL Editor)

```sql
update public.profiles set plan = 'plus' where email = 'someone@example.com';
update public.profiles set trial_ends_at = now() + interval '30 days' where email = 'someone@example.com';  -- give a new trial
```

## Change a limit

```sql
update public.plans set max_medicines = 12 where key = 'plus';
```

## Add a plan

Insert a row into `public.plans` (key, name, limits, sort_order). The Plans screen lists every row; add the key under `plans.names` in `web/src/messages/{en,hi}.json` and to the list in `web/src/screens/Plans.tsx` for a translated name.

## What is paused after the trial (or on any plan that is below what the account holds)

Reminders above the plan's limits are **paused**, never deleted:
- parents beyond `max_parents` (the oldest stay): no medicine reminders, check-ins or appointment reminders;
- medicines beyond `max_medicines` per parent (the oldest active ones stay; medicines the family paused by hand do not count).
People already in a circle stay; only new invites are blocked.
Upgrading (or a new trial) resumes everything on the next tick (about a minute). Pending doses of a newly paused medicine are removed.
The family is warned: a red card on Today (for the whole circle), a "Paused: plan limit" tag on the medicine, the Plans screen, and,
in the last 7 days of the trial, a banner saying how many medicines/parents WOULD pause. Code: `paused_recipients()`, `paused_medicines()`,
`plan_pauses()` in migration 0027, and the skips in `api/app/services/scheduler.py` (check-ins, appointment reminders).

## Where the limits are enforced

`limit_parents` and `limit_medicines` triggers (so direct table writes are covered too), `create_family_invite` and `accept_family_invite` (circle size), `guard_profile_plan` (nobody changes their own plan or trial). Tests: `supabase/tests/plans.test.sql`.
