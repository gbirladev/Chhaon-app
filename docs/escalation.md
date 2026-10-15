# Escalation ladder (phase 1: no helper yet)

When a parent does not answer, Chhaon should escalate in steps and only bother the family when it matters. Phase 2 (a trusted nearby helper, with its own consent flow and templates) comes later and builds on this.

## Decisions used (change them if you disagree)
- Off by default for every parent (`escalation_policies.enabled`), plus a server switch `ESCALATION_ENABLED` (not built yet).
- A WhatsApp alert goes to the family only after **2 unanswered prompts in a row** (setting 1 to 3 per parent), or on the **first** miss of a medicine marked **critical**. A missed daily check-in plus an unanswered dose counts as 2.
- A "prompt" is a medicine time slot (all medicines due at the same moment) or a daily check-in; any reply of any kind resets the run.
- Defaults (minutes after the reminder was sent): doses nudge 15, alert 45; check-in nudge 60, alert 120.
- A parent who stays silent for `silent_stop_days` (3) stops being nudged and alerted; the family gets one summary instead.
- A confirmation after a dose was marked missed is **`taken_late`** (counts as confirmed everywhere; stats include it).
- "I've handled it" in the app stops the escalation of one prompt for everyone.

## Built so far (step 1, database, 2026-10-15)
- Migration 0031: `taken_late` status. Migration 0032: `medicines.critical`, `escalation_policies` (per parent, constraints, RLS), `escalations` (state per slot or check-in, written by the backend only, readable by the family), `handle_escalation()`, `set_medicine_critical()`, `confirm_dose_slot()` records `taken_late`, Pilot stats count `taken_late`.
- API: a late tap on **Taken** or **Taken all** becomes `taken_late`; stock is used up once.
- Web: Today shows "Taken late" (counted as done in the progress), the WhatsApp history shows the reply.
- Tests: `supabase/tests/escalation.test.sql` (17), `supabase/tests/slot_reminders.test.sql` (8), API tests updated (252 pass), 230 database tests pass.

## Still to build
1. **Backend (step 2):** the scheduler step that creates an escalation per unanswered prompt, sends the nudge, applies the "unanswered in a row" and critical rules, marks missed at the alert time, alerts the family, resolves on a reply (and tells those already alerted), the silent-parent stop, and the server switch.
2. **Templates (step 3):** `carelink_parent_dose_nudge`, `carelink_parent_checkin_nudge`, and a caregiver "parent replied" alert, in English and Hindi, plus the pack and tests.
3. **Screens (step 4):** per-parent Safety net settings (presets, switch, unanswered count), a critical toggle on each medicine, escalation progress on Today with "I've handled it".
4. **Phase 2:** helper onboarding, consent, requests and replies, quiet hours, daily cap, parent consent, expiring emergency link.
