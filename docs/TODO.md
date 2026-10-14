# TODO (things outside the code, or decisions parked)

## Before real families use it
- [ ] **Meta WhatsApp Business setup + template approval.** Free to set up; messages are charged per delivered template
      (rough pilot estimate: ~₹400–500/month for 30 families; confirm on Meta's rate card). Approval takes days, so submit early.
      Steps and exact template text: `docs/whatsapp-setup.md`.
- [ ] **Opt-in policy / legal check.** Our consent template is a business-initiated first message. Verify against Meta's current
      policy, and have a health-tech lawyer review consent + privacy wording (DPDP Act 2023) before public launch.
- [ ] **Hindi copy review by a native speaker** (consent and medical wording first). Files: `web/src/messages/hi.json`,
      `api/app/whatsapp/templates.py`, `api/app/services/messaging.py`.
- [ ] **Real SMS provider for phone OTP** (Twilio / MessageBird). Local dev uses a test number (`+91 99999 99999`, code `123456`).
- [ ] **Production hosting:** Supabase project in a Mumbai region, a Python host for `api/` (preferably India), static host for `web/`.
- [x] Privacy policy + terms pages, consent, export, delete (step 11). **Still needed:** lawyer review of `web/src/content/legal.ts` (placeholders in [brackets]), Hindi review, grievance officer details.

## Parked decisions
- [ ] Caregiver alert channel beyond the in-app bell (missed dose / no check-in): WhatsApp message to the caregiver, web push, or email.
- [ ] Real PWA install test over `https://` (needs a tunnel or a preview deploy; an ngrok tunnel was blocked, so the user runs it or approves a deploy).
- [ ] Country-code picker for the caregiver login (NRIs). A version exists in `docs/wip-supabase-ui/`.
- [ ] Replace the `age` column with date of birth when refactoring the profile form.
- [ ] Capacitor wrapper for Android/iOS stores, only if the pilot asks for it.

## Found by the QA round (2026-10-10), not fixed yet
Security / launch hardening
- [ ] **Email confirmation is off and the minimum password length is 6** (local Supabase config). Turn confirmations on and raise the minimum before launch.
- [ ] **OTP errors leak details:** unknown emails reveal that no account exists, and the phone error names the SMS provider. Use one generic message.
- [ ] **Production headers / CSP:** `web/vite.config.ts` carries the recommended header block as a comment. Apply it at the real host (CSP, nosniff, referrer policy, frame denial, permissions policy).
- [ ] **Production secrets:** `ENVIRONMENT=production` (closes `/docs`, narrows CORS), a real `WHATSAPP_APP_SECRET` and `CRON_SECRET`.
- [ ] **Storage:** uploads only check the declared content type; add server-side file sniffing. Signed-URL lifetime is chosen by the client.
- [ ] **Reports / appointments** tables are not used by the UI yet; re-run the security review when steps 7-8 land.

Product / reliability
- [ ] **Scheduler heartbeat:** nothing alerts the team when the 5-minute tick stops running (doses are then silently not sent). Add a heartbeat row + an external monitor.
- [ ] **Send timeout duplicates:** a WhatsApp send that succeeds at Meta but times out on our side is retried, so the parent may get a duplicate reminder.
- [ ] **Offline app shell:** a logged-in user who reloads offline sees "try again" after the retry delay; a proper offline screen would be nicer.
- [ ] **Text size in `rem`:** sizes are now rem with a 12px floor; a full pass to rem for every unit would honour system font scaling everywhere.
- [x] **Code splitting:** done. The JS is split into index (about 70 KB gzip), App (about 101 KB gzip), the Hindi texts (about 31 KB gzip) and the tiny public emergency page; only what the screen needs loads.
- [ ] **Dark mode** is not designed (the app forces a light colour scheme).
- [ ] **Doctor and emergency contacts** are in the schema but the Figma profile form does not collect them, so the emergency card shows caregivers' phones instead.
- [ ] **Real family invites** (step 9): the Invite button says "coming soon"; roles are owner + member.
- [ ] **Medicine editing** (name, times, dose): only stock, pause and delete exist today.

Open items from the second backend review
- [ ] **Consent-request denial of service:** someone can enrol a victim's number and keep the legitimate family's request in "too soon" (10 per caregiver per day, several accounts). Mitigate before launch: verify caregiver identity (phone OTP) before the first send, let a parent's own reply or a verified owner override, and watch the `consent_requests` log for numbers hit by many accounts.
- [ ] **Migration 0010** dedupes schedules by deleting the later duplicates (their dose history cascades) and its NOT VALID constraints are enforced on later updates of any legacy row that violates them. Local data had no violators; check production data before applying.
- [ ] **Scheduler tests that call the whole-database `run_tick`** can pass because of other data in the database. Move to a scoped tick (recipient ids) so tests stay isolated.
- [ ] An exception between claiming a dose and the send `try` (template building) would leave it `sent` with nothing sent. No reachable trigger today; wrap it when touching that code.

Found by the journey and verification agents (round 3), still open
- [ ] **Archive instead of delete for medicines:** deleting a medicine also deletes its dose history (cascade). For health records, prefer "archive" (hidden, history kept) and keep delete for mistakes.
- [ ] **Offline app shell** and the ~8 s "Loading…" before the retry notice when the backend is down.
- [ ] **Reload on `#/profile`** goes to Today (by design); a parent deleted mid-edit drops the user on a blank add-parent form without a message.
- [ ] **WhatsApp preview** shows the parent's first real medicine; a fuller preview (real reminder + check-in templates exactly as approved by Meta) once templates are approved.

- Caregiver WhatsApp alerts: no quiet hours / caregiver-timezone handling yet; Hindi alert copy needs native review; alert templates need Meta approval (see whatsapp-setup.md).
- Medicine edit: removing a reminder time deletes that time's dose history (cascade). Consider ending the schedule instead.
- **Report reading (step 7) is built but OFF for v1** (`VITE_REPORTS_ENABLED=false`: the Reports tab shows "coming soon"). Backend `POST /reports/{id}/analyze` is ready (needs `ANTHROPIC_API_KEY`). Turn on after pilot signals: set the flag, add the key, have a doctor/lawyer review the summary wording, and recompute flags when a value is corrected.
- **Before the pilot starts:** fill the [bracket] placeholders in `web/src/content/legal.ts` (company/operator name, grievance contact email, backup period, governing law), have any native Hindi speaker read the Hindi text, and publish the same privacy text at a public web address (Meta asks for a privacy-policy URL). Lawyer review can wait until public launch or charging, except the WhatsApp opt-in flow.
- **Today style picker:** 10 styles in `web/src/screens/DoseLayouts.tsx` (route, curvy, timeline, arc, focus, ring, pillbox, ladder, clock, chat), saved per caregiver in `profiles.today_layout`. Once real users choose, remove the styles nobody uses. Hindi screenshots of the newer styles still need a visual check.
- **Sample data on the owner's test account (+91 99999 99999):** extra medicines (Thyroxine, Calcium, Atorvastatin, Omega-3, Vitamin D3), appointments, notifications, two fake family members (Priya, Rohan), sample reports and an emergency link. Remove before any real use.
- **Privacy is ON for v1, in short plain words** (`web/src/content/legal.ts`, no placeholders, not reviewed by a lawyer or a Hindi reader by choice). Switch: `VITE_PRIVACY_ENABLED`. Before a public launch: Meta wants a public privacy-policy URL, and a lawyer should check the opt-in flow.
- **If the product is renamed later:** the name "CareLink" is inside these WhatsApp templates: `carelink_consent_request_v2`, `carelink_alert_optin`, `carelink_daily_checkin` (en + hi), and the Hindi `carelink_alert_call_requested` and `carelink_alert_parent_stopped`. Edit their text in WhatsApp Manager (each goes back for review), update `docs/whatsapp-templates.*`, the app texts, and the WhatsApp business display name.
- WhatsApp templates were created in WhatsApp Manager on 2026-10-12 (14 templates, en + hi; the two permission-asking ones as Marketing). Texts live in `docs/whatsapp-templates.md/json`, checked against the code by `api/tests/test_template_pack.py`.

## Where we paused (2026-10-12)
- **Done:** v1 app built and tested (226 API + 129 database tests). Privacy ON (short plain text). Reports OFF. Name stays CareLink.
- **WhatsApp:** 14 templates submitted in WhatsApp Manager (en + hi), all "in review". Meta developer app created (use case "Connect with customers through WhatsApp"). Texts: `docs/whatsapp-templates.md`.
- **Next, in order:** (1) Meta "Step 2 Production setup": register a real phone number (spare SIM, display name CareLink) and add a payment method. (2) Host `web/public/legal/*.html` (e.g. Netlify Drop) and put the two URLs in Meta App settings > Basic, with category and icon. (3) Deploy the API + scheduler + web over HTTPS (see `docs/deploy.md`). (4) Webhook: callback `https://<api-host>/webhooks/whatsapp`, verify token = `WHATSAPP_VERIFY_TOKEN`; publish the Meta app. (5) Put the 4 WhatsApp values in `api/.env` (never paste them in chat). (6) First real test with your own number. (7) Remove the sample data on the owner's test account. (8) Pilot with 20 to 30 families.
- Note: with the real token set, the local API/scheduler really sends WhatsApp messages; use a test parent with your own number.

- **Renamed to Chhaon (2026-10-12).** The app, install name, privacy pages and the app's own WhatsApp replies now say Chhaon / छाँव. Still to do: edit the 5 WhatsApp templates in WhatsApp Manager after approval (see the note at the top of `docs/whatsapp-templates.md`); choose "Chhaon" as the WhatsApp display name; check the domain; a trademark attorney opinion before a public launch (the registered mark CHHAON FOUNDATION, class 45, expires 07/11/2026 unless renewed). Internal names stay `carelink_*`.

## Deployment status (2026-10-12)
- **Done:** Supabase project created in Mumbai; all 25 migrations pushed with `npx supabase db push` (never run `supabase config push`: local config has test login codes). Phone login is disabled in the dashboard (email login for the pilot). Repo is on GitHub with `render.yaml` (API + one-minute cron) and `web/netlify.toml`.
- **Next:** (1) Render: New > Blueprint > repo `gbirladev/Chhaon-app`, fill the secrets listed in `render.yaml`; leave the WhatsApp token and number id empty (practice mode); set `API_URL` on the cron job to the API address; check `/health`. (2) Netlify: connect the repo, base directory `web`, set `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_API_URL`, `VITE_REPORTS_ENABLED=false`, `VITE_PRIVACY_ENABLED=true`. (3) Supabase Auth: Site URL + redirect URL = the web address, Confirm email on, password length 8, custom SMTP (Brevo/Resend). (4) Change the first screen to lead with email login (phone login is off). (5) Meta: webhook callback `https://<api>/webhooks/whatsapp`, verify token = `WHATSAPP_VERIFY_TOKEN`, privacy/terms URLs, publish the app, then put the 4 WhatsApp values into Render.
- Render could not be opened on the Mac (Bitdefender blocked it); continue from another computer.

## Plans, trial and pausing: open gaps (2026-10-14)
Built and tested locally (166 database + 235 API tests, browser checks in English and Hindi). Details: `docs/plans.md`. **Not on the live database or site yet.**

- [x] **Went live 2026-10-14:** migration 0027 applied with `db push` (dry run first), owner account `gbirla.dev.rnd@gmail.com` put on `ultra`, code pushed (commit f2e6ea1), Plans screen confirmed on the live site. The original order was:
  (Go live with plans, in this order: (1) back up the live database (or note its backup state in Supabase); (2) `npx supabase@2.120.0 db push` (use that version: 2.121.0 had no Mac build; never `config push`); (3) `git push` so Netlify and Render redeploy; (4) put the owner's own account on `ultra` (`update public.profiles set plan = 'ultra' where email = '...'`), otherwise it is on Free after its 30-day trial.
- [ ] **Run the migration against real data once:** it was tested from the live database's state on a scratch copy, not on the live data itself. Check afterwards that existing accounts show a 30-day trial and that Today, Medicines and Plans load.
- [ ] **Look at it on a real phone** (only a phone-sized browser window was used).
- [ ] **"Invite a family member" message for a Free user** (the circle limit): refused by the database (tested) but the on-screen message was not looked at.
- [ ] **Hindi wording for plans, trial and the pause warning** has not been read by a native speaker (also covers the older Hindi texts).
- [ ] **Speed at scale:** the pause check (`paused_recipients`, `paused_medicines`) runs on every scheduler tick over all parents and medicines. Fine for a pilot; measure before thousands of families.
- [ ] **WhatsApp message to caregivers when the trial ends / reminders pause:** needs a new Meta template and approval. For now the warning is only inside the app (red card on Today, medicine tags, Plans screen, banner in the last 7 days of the trial).
- [ ] **People in a circle are not paused** when a plan shrinks (only new invites are blocked). Decide later whether over-limit members should lose access.
- [ ] **Prices are not set.** Working guesses to test in the pilot: Plus about Rs 199 a month, Family about Rs 399, higher for NRIs. Ultra should be priced above its worst case (20 parents at about Rs 40 each, about Rs 800 a month in WhatsApp fees). Nothing is charged yet and there is no payment code.
- [ ] **Cost numbers are estimates** from third-party pages (Utility about Rs 0.115 per message, Marketing about Rs 0.86, GST extra; a Utility template inside an open 24-hour window is free per Meta). Check Meta's India rate card and current hosting prices and the exchange rate before setting prices.

## Meta and WhatsApp: state and open items (2026-10-14)
- **Working end to end:** consent message, medicine reminder with the Taken tap, Hindi templates, replies reaching the server. The missing step that blocked replies was subscribing the WhatsApp account to the app (`POST /v21.0/<waba-id>/subscribed_apps`).
- [ ] **Render `CONSENT_RESEND_MINUTES` is 5** (default 10). Fine for the pilot; reset to 10 if parents get annoyed by repeat requests.
- [ ] **Meta app is still in Development mode:** "Publish" is blocked until business verification. Replies work without it for the pilot. If Meta ever stops delivering replies, business verification (registered business) is the way out. Alternatives looked at: SMS (needs DLT and entity documents), automated voice calls (possible fallback), unofficial WhatsApp tools (not allowed, ban risk), a WhatsApp provider (still uses Meta).
- [ ] **Edit the 5 templates that still say "CareLink"** and set the WhatsApp display name to Chhaon (it already shows Chhaon).
- [ ] **Retest the consent message with the 24-hour window closed** (no message from the test number to the business number for a day), so we know it also arrives without an open window.
- [ ] **Test the daily check-in** (it sends in the morning, in the parent's time zone) and a **reminder with a medicine photo**.
- [ ] **Watch the payment balance** in WhatsApp Manager (prepaid funds); sending stops when it runs out.
- [ ] **Hosting:** Render free plan sleeps (cron-job.org wakes it every minute); move to a paid plan and a real domain before the pilot grows. Supabase free plan has no backups: consider Pro.
- [ ] **Pilot invite message** (English and Hindi) was drafted in chat, not saved here.

## Pilot stats page (built 2026-10-14, see `docs/stats.md`)
- [x] **Live since 2026-10-14:** migration 0028 applied, code pushed (d0f8bc2), `gbirla.dev.rnd@gmail.com` is the admin, and the owner confirmed Pilot stats shows in the app.
- [ ] **Look at it on a real phone**, and read the Hindi wording (not read by a native speaker).
- [ ] "Active this week" is a proxy (last login or last session refresh). Replace it with a real usage signal if it proves too rough.
- [ ] The Meta fee figure uses third-party rates (Utility about Rs 0.115, Marketing about Rs 0.86): confirm on Meta's rate card.

## Reports wallet (built 2026-10-14, see `docs/wallet.md`)
- [ ] **Go live with it:** `npx supabase@2.120.0 db push < /dev/null` (migration 0029), then `git push`. No admin step needed.
- [ ] **Look at it on a real phone:** the camera button (`capture`), picking several photos from the gallery, and a real PDF. Only a browser window was used; HEIC photos from some phones need checking.
- [ ] **Decide plan limits for the wallet** (storage costs money): for example Free 3 reports, Plus 30, Family 100, Ultra the 200 ceiling. Today every plan gets 200.
- [ ] **Hindi wording** has not been read by a native speaker.
- [ ] **Storage cost check:** photos are about 100 to 400 KB after shrinking, PDFs up to 15 MB. Watch the Supabase storage size in the dashboard once families start uploading (the free plan includes 1 GB).
- [ ] **Old AI report reading** (reports, report_values, the analyze endpoint) is still in the code, switched off. Decide later whether to remove it or build "read values" on top of the wallet.
