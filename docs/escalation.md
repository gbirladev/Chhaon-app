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

## Built in step 2 (server logic, 2026-10-15)
- `api/app/services/escalation.py`, run as a scheduler step after the check-ins. It follows every unanswered prompt (a medicine time slot or the daily check-in) from the moment the reminder was sent: **nudge** (15 / 60 min), **decide** (45 / 120 min: mark missed; alert the family only if the unanswered run reaches the parent's number or a critical medicine is involved), **resolve** on any reply (the family is told only if it had been alerted).
- Steps are claimed with guarded updates (no duplicates, safe to run twice or late). A nudge never goes out after the alert time; nothing at all is sent for a prompt older than 24 hours (the record is still marked missed).
- A parent who has answered nothing for `silent_stop_days` days stops being nudged and alerted about; the family gets one summary (in the app and on WhatsApp). Reminders and check-ins to the parent continue (they are the core product; roughly the cost of a silent parent, about Rs 40 a month).
- Parents with the ladder on are skipped by the old "missed after 60 minutes" rule. Everyone else is unchanged. Server switch: `ESCALATION_ENABLED` (default off) AND the parent's own policy.
- Four new templates in the pack (sections 16 to 19): parent dose nudge, parent check-in nudge, "parent replied" alert, "parent silent" alert. New notices in the app for "replied", "silent" and "N medicines".
- Tests: `api/tests/test_escalation.py` (30), 282 API tests and 230 database tests pass.

## Built in step 4 (screens, 2026-10-15)
- Family tab > **Safety net** (shown only when the server's `/features` says the ladder is on): per-parent switch, speed presets (Gentle / Standard / Fast), "alert me after" (1, 2 or 3 unanswered), silent-parent days (2, 3, 5, 7), a critical toggle per medicine, and a plain "not an emergency service (112)" notice.
- Medicines list shows a "Critical" tag. Today shows a "Waiting for {name}'s reply" card (sent, nudged, alerted) with **I've handled it**.
- English and Hindi. Browser-tested end to end (settings saved to the database, card resolved, nothing shown when the server switch is off).

## Still to build
1. ~~**Backend (step 2):**~~ done, see above. Original list: the scheduler step that creates an escalation per unanswered prompt, sends the nudge, applies the "unanswered in a row" and critical rules, marks missed at the alert time, alerts the family, resolves on a reply (and tells those already alerted), the silent-parent stop, and the server switch.
2. **Templates (step 3):** `carelink_parent_dose_nudge`, `carelink_parent_checkin_nudge_v2`, and a caregiver "parent replied" alert, in English and Hindi, plus the pack and tests.
3. **Screens (step 4):** per-parent Safety net settings (presets, switch, unanswered count), a critical toggle on each medicine, escalation progress on Today with "I've handled it".
4. **Phase 2:** helper onboarding, consent, requests and replies, quiet hours, daily cap, parent consent, expiring emergency link.
