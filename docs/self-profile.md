# "Me": your own medicines (the caregiver as a profile)

A caregiver can add THEMSELVES and get medicine reminders on their own WhatsApp, like a parent. Built 2026-10-17, not pushed.

## Decisions (owner, 2026-10-17)
- It does **not** count toward the plan's number of parents (Free: 1 parent + you).
- It keeps the plan's medicine limit per profile (Free: same as a parent).
- It is **private**: it lives in its own circle, nobody can be invited to it or added to it (database triggers), siblings never see it.
- No daily check-in for yourself.

## How it works
- Parent switcher > **Add myself** (shown until you have one). Form: your name, your WhatsApp number, language, optional health details.
- Database (migration 0036): `care_recipients.is_self`, relation `self`, one per person, cannot be changed to or from a parent profile, cannot share a circle. `limit_parents`, `paused_recipients` and `plan_overview` ignore it. `create_care_recipient` takes `is_self`.
- Confirmation: your number gets the template **`carelink_self_optin`** (Marketing, Yes / No buttons; docs/whatsapp-templates.md section 20). The Yes tap uses the normal consent flow, so STOP works as usual. Until Meta approves the template, the confirmation cannot be completed on real WhatsApp (dry-run and tests work).
- Backend skips for your own profile: the daily check-in, the escalation ladder, love messages, and these notifications: missed dose, check-in missed, call requested, consented/declined/stopped/resumed, parent replied/silent, appointment reminder sent, parent messages (`SELF_QUIET` in `services/notify.py`). Refill, out-of-stock, "reminder not sent" and "WhatsApp failed" stay.
- Screens: no check-in line, no "missed dose, call" card, no invite section (a private note instead), no Safety net or Love messages entries, the pending-confirmation banner has its own wording (English and Hindi).

## Same number on two profiles (migration 0037, 2026-10-17)
- Your own profile cannot share a number with another profile of yours, in either direction (the database refuses it; the form warns as soon as the number is typed and shows a clear message on save).
- Two PARENTS may share one number (a couple with one phone). The form shows a friendly note. Tested end to end (`api/tests/test_shared_number.py`): each parent gets their own reminder addressed by name, a tap confirms only that dose, each consents with their own Yes tap, the second consent request to the same number waits for the window, STOP stops both and START restores both, each gets a check-in and a reply belongs to one.

## Known gaps
- The Pilot stats page counts your own profile as a parent (`admin_stats` was not changed). Fix when it matters.
- Medicine screens still say "{name}" where they would say "you"; fine for now.
- Needs one real test on WhatsApp after Meta approves `carelink_self_optin`.
