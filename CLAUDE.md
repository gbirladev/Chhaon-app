# CLAUDE.md — Project Context

This file gives Claude Code the full context of this project. Read it before making changes.
Full product plan and interview questions: `docs/PRODUCT_PLAN.md`.

## What we are building

A family health and care app for adult children who live away from their aging parents
(another city in India, or abroad as NRIs). Working name: **Chhaon** (placeholder, may change).

**The problem:** Children can't easily track whether parents take medicines, what their lab
reports say, or when appointments and refills are due. Today this is managed through phone
calls, WhatsApp chats and paper folders.

**The core idea:**
- The **child (caregiver)** uses our app (web app / PWA first).
- The **parent** does NOT install anything. They interact only through **WhatsApp**
  (reminders + one-tap replies). This is the most important product decision — never add
  anything that requires the parent to install an app or create an account.

**Market:** Start in India. Paying users are likely NRIs (US, UK, UAE, Canada, Australia)
plus urban Indian professionals. Later expand to other diaspora communities.

## MVP scope (version 1)

### Caregiver (child) side
1. Sign up / log in (email or phone OTP).
2. Create a parent profile: name, age, phone (WhatsApp), timezone, conditions, allergies,
   blood group, doctor contacts.
3. Medicine schedule: name, dosage, times (morning/afternoon/night or exact time),
   start/end date, optional photo of the strip/bottle, stock count for refill alerts.
4. Today dashboard: which doses were taken / missed / pending, today's check-in status.
5. Alerts to caregiver when a dose is missed or the daily check-in gets no reply.
6. Report & prescription upload (photo or PDF) → AI extracts key values
   (sugar, HbA1c, BP, thyroid, cholesterol, etc.), gives a simple explanation in
   English or Hindi, and shows trends over time.
7. Appointments and refill reminders.
8. Family sharing: invite siblings/relatives to the same parent profile.
9. Emergency card: a shareable link (no login needed, unguessable token) with medicines,
   allergies, conditions, blood group and contacts.

### Parent side (WhatsApp only)
1. Medicine reminder at scheduled time, with medicine photo, and a button "✅ Taken".
2. Daily morning check-in: "Good morning! Are you okay?" with a one-tap reply.
3. If no reply within a set window (e.g. 60 min for dose, 3 hours for check-in),
   notify the caregiver(s).
4. Messages in the parent's preferred language (English, Hindi first; more later).

### Explicitly OUT of scope for v1
Doctor consultations, home services, lab bookings, wearables, fall detection,
payments to partners, native parent app. Do not build these unless asked.

## Tech stack (confirmed)

| Part | Choice | Notes |
|---|---|---|
| Frontend (`web/`) | Vite + React + TypeScript, installable PWA | Built from the Figma export (`docs/design/`). All UI text is in `web/src/messages/{en,hi}.json` (use-intl) |
| Backend API (`api/`) | Python FastAPI (managed with `uv`) | WhatsApp webhook + sending, scheduler, AI report reading. Uses the Supabase service role |
| DB / Auth / Storage | Supabase (Postgres), local via `npx supabase start` | Migrations in `supabase/migrations`, RLS on every table, pgTAP tests in `supabase/tests` |
| Parent messaging | WhatsApp Business Cloud API (Meta) | See `docs/whatsapp-setup.md`. Dry-run mode until credentials are set |
| Scheduling | Cron job every 5 min calling the API (step 4) | Sends due reminders, checks for missed replies |
| AI report reading | Anthropic Claude API (vision + PDF) | Check docs.claude.com for current model names |
| Payments (later) | Razorpay (India, UPI) + Stripe or Razorpay international | Not in first build |
| Hosting | Static host for `web/`, a Python host for `api/` (prefer India), Supabase in a Mumbai region | Health data |

Decisions made along the way: family roles are owner + member only (no view-only in v1); the emergency link has no
expiry and can be revoked; the parent's consent belongs to one WhatsApp number (changing the number resets it);
the app UI is faithful to the Figma, refactor later if needed.

**Build status:** steps 1-4 done and QA-tested (auth, schema + RLS, parent profile, medicines, WhatsApp send/receive with consent + STOP,
scheduler, real Today dashboard, low-stock alerts, several parents per caregiver, Hindi/English everywhere). Waiting on real Meta
WhatsApp credentials + template approval. Not built yet: reports + AI reading (7), appointments (8), family invites (9), secure emergency
link (10), privacy pages + polish (11). See `docs/TODO.md`.

**Update:** steps 9 (invites), 10 (emergency link) and 11 (privacy pages, consent, export, account deletion) are now built and tested.
Step 7 (report reading with AI) is built but switched off for v1 (`VITE_REPORTS_ENABLED=false`, Reports tab shows "coming soon").
Step 8 (appointments + WhatsApp reminders) is built. A QA round with 6 agents found and fixed security, time-zone, invite and UI bugs. The Today screen has a per-user style picker (10 styles). All suites pass (226 API tests, 129 database tests).

**Update (plans):** pricing plans with enforced limits are built (Free / Family, migration 0027, `docs/plans.md`). Nothing is charged; plans are switched by hand in the Supabase SQL editor. Reports and payments are still off.

## Data model (starting point)

- `profiles` — caregiver users (id, name, email, phone, country, timezone, language)
- `care_circles` — a group around one parent (id, created_by)
- `care_circle_members` — (circle_id, user_id, role: owner | member)
- `care_recipients` — the parent (id, circle_id, name, dob, whatsapp_phone, timezone,
  language, conditions[], allergies[], blood_group, emergency_contacts json, consent_at)
- `medicines` — (id, recipient_id, name, dosage, photo_url, stock_count, active)
- `medicine_schedules` — (id, medicine_id, time_of_day, days_of_week, start_date, end_date)
- `dose_events` — (id, schedule_id, scheduled_at, status: pending | sent | taken | missed,
  sent_at, responded_at, whatsapp_message_id)
- `checkins` — (id, recipient_id, date, sent_at, responded_at, status)
- `reports` — (id, recipient_id, file_url, report_date, type, ai_summary, ai_status)
- `report_values` — (id, report_id, test_name, value, unit, reference_range, flag)
- `appointments` — (id, recipient_id, doctor_name, datetime, notes)
- `emergency_links` — (id, recipient_id, token, active)
- `notifications` — (id, user_id, type, payload, read_at)

## Important rules

**Timezones:** Reminders always use the PARENT's timezone (usually Asia/Kolkata). The
caregiver may be in another timezone — show times in both where helpful. Store everything
in UTC.

**WhatsApp:**
- Business-initiated messages outside the 24-hour window must use **pre-approved
  templates** (utility category). Design reminders and check-ins as templates with
  quick-reply buttons.
- Get the parent's **opt-in/consent** before sending any message; record `consent_at`.
- Handle "STOP" to opt out.
- Verify webhook signatures. Make webhook handling idempotent (Meta can resend).

**AI and medical safety:**
- The AI EXPLAINS reports; it never DIAGNOSES or recommends changing medicines.
- Always show: "This is not medical advice. Please consult your doctor."
- If a value is far outside the reference range, highlight it and suggest contacting a doctor.
- Ask the model to return strict JSON for extracted values; validate before saving.
- Let the user review and correct extracted values.

**Privacy and security (India DPDP Act 2023):**
- Clear consent from caregiver and parent; explain what data is stored and why.
- Row Level Security: users only see data of circles they belong to.
- Store files in private storage buckets; use signed URLs.
- Emergency link shows only minimal necessary info; caregiver can revoke it.
- Allow data export and account deletion.
- Never log health data or phone numbers in plain logs.

**UX principles:**
- Mobile-first, works on low-end Android and slow networks.
- Very simple language. Large tap targets.
- Parent messages must be short, friendly and respectful (e.g. "Namaste Papa 🙏").
- Support English and Hindi from the start (i18n-ready for other Indian languages).

## Build order

1. Project setup, auth, Supabase schema + RLS.
2. Parent profile + medicine schedule screens.
3. WhatsApp integration: send template, receive button reply via webhook.
4. Scheduler: create dose events, send reminders, mark missed, alert caregivers.
5. Today dashboard.
6. Daily check-in flow.
7. Report upload + AI extraction + trends.
8. Appointments, refill reminders.
9. Family sharing (invites).
10. Emergency card link.
11. Polish, i18n (Hindi), privacy policy page, pilot with 20–30 families.

## Success metrics for the pilot

- % of medicine reminders confirmed by parents
- Weekly active caregivers
- Families still active after 4 weeks
- Number of pilot families willing to pay
