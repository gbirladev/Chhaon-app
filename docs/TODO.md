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
- [x] **Live since 2026-10-14:** migration 0029 applied, code pushed (a97a4b8), and the owner confirmed on a phone that the Reports wallet works.
- [x] **Tried on a real phone** (owner: "it works"). Then stress-tested in a browser (2026-10-14), all passing: a 14.5 MB PDF saves and a 15.5 MB one is refused with a clear message; an 8000x6000 photo is shrunk from 3.3 MB to 0.5 MB; a photo with a blank file type is accepted by its name (this was a bug, fixed); a double-tap on Save makes one report; a 3G-speed connection works (about 16 s for a 1 MB photo, button shows "Uploading…" and is disabled); a cut connection during the upload or the save shows a clear error, keeps the form, and a retry works.
- [ ] **HEIC photos** (iPhone "High Efficiency" format): a browser that cannot read them gets a clear message with the fix (iPhone: Settings > Camera > Formats > Most Compatible). iPhone Safari normally converts them to JPEG by itself, but that was NOT tested on a real iPhone. Supporting HEIC properly needs a converter library (about 1 MB); only worth it if families hit this.
  - Decision 2026-10-17: keep as is for now. First test one real iPhone photo (Reports wallet and the feedback pictures). If it fails, add a converter (`heic2any`, ~2.7 MB unpacked, probably ~1 MB downloaded; sizes are estimates) loaded ONLY when a HEIC file is picked, kept out of the offline pre-cache, so normal loading stays the same; measure the real size and speed then.
- [ ] **Known small gap:** if the phone is offline at the exact moment a save fails AND the clean-up of the just-uploaded files also fails, those 1 to 2 files stay in storage unreferenced (invisible; removed when the parent or account is deleted). Idea: a daily job that deletes `reports` files older than a day that no record points to.
- [x] **Plan limits for the wallet decided 2026-10-14:** Free 5, Plus 25, Family 100, Ultra unlimited (ceiling 200), plus a hidden 300 MB per parent. Existing reports are never hidden or deleted over a limit. Revisit once real usage is known.
- [ ] **Hindi wording** has not been read by a native speaker.
- [ ] **Storage cost check:** photos are about 100 to 400 KB after shrinking, PDFs up to 15 MB. Watch the Supabase storage size in the dashboard once families start uploading (the free plan includes 1 GB).
- [ ] **Old AI report reading** (reports, report_values, the analyze endpoint) is still in the code, switched off. Decide later whether to remove it or build "read values" on top of the wallet.

## Netlify credits (2026-10-14)
- Netlify's free plan has **300 credits a month** (a hard limit: the site pauses when they run out). A production deploy costs **15**, 10,000 web requests cost 2, 1 GB of traffic costs 20 (rates from Netlify's docs).
- By 2026-10-14 half the credits were gone, almost all from deploys: every `git push` to `main` built and published the site, including pushes that only changed the API, database, tests or docs (about 7 of the 14 pushes since 12 Oct, roughly 100 credits).
- [x] **Fixed in `web/netlify.toml`:** an `ignore` rule skips the build when nothing in `web/` changed. Checked against the last 10 pushes: 6 would have been skipped.
- [ ] **Batch website changes** into fewer pushes (each real website push is still 15 credits, so about 20 a month fit).
- [ ] **Watch the balance** (Netlify > Team settings > Billing) and decide before the pilot: stay on Netlify (Personal plan about $9 a month at the time of writing, check), or move the website to a host with free unlimited requests (Cloudflare Pages is the usual choice). Traffic itself is tiny: 1,000 visits is only a few credits.

## Android app wrapper and Play Store (discussed 2026-10-15, DROPPED for now)
Decided on 2026-10-15 not to pursue an Android app or a Play Store listing. The app stays a web app that can be added to the home screen (PWA). Nothing was built. What we found, in case it is revisited:
- Google Play lists **health apps as needing an organization account** (registered business, usually a D-U-N-S number), which also shows the developer's name publicly.
- Needed for a listing: an .aab file, store assets, a privacy policy link, the Data safety form, a health apps declaration, in-app and web account deletion, and (personal accounts) a 12-tester, 14-day closed test.
- The web app's manifest is already wrapper-ready (name, standalone, portrait, 192/512 icons, a maskable icon). No Java, Android SDK, adb or Bubblewrap is installed on the Mac.
- **Easiest route:** PWABuilder (pwabuilder.com, enter https://chhaon.netlify.app, package for Android). It returns an installable file plus a Play Store bundle and shows the signing key's SHA-256 fingerprint.
- **Then:** add that fingerprint to `web/public/.well-known/assetlinks.json` so the app opens full screen without an address bar (needs one website deploy; batch it with other web changes, Netlify credits).
- **Sideloading** (sharing the file directly) is fine for a few people you know; Android shows an "unknown app" warning.
- **Play Store:** developer account fee, a closed test with 12 testers for 14 days for a new personal account (organizations are exempt), a health-app declaration, the privacy page and the data-safety form. The developer's name is shown publicly. Do this only after Vengage's written permission and the business registration.
- **Apple App Store** is harder (Apple's guideline 4.2 rejects thin web wrappers) and costs about $99 a year; not planned.

## Website feedback round (2026-10-15)
- [x] **Pull to refresh** built (`web/src/lib/pullToRefresh.ts`, `screens/Install.tsx` indicator). The phone's own version was switched off by `overscroll-behavior-y: none`. Pulling down at the top of Today, Medicines, Reports, Family, Plans, Stats and the detail screens reloads the shared data and reopens the screen. It is ignored while scrolled down, while a sheet is open, and on forms (so typed text is never lost). Tested with real touch gestures.
- [x] **Add to home screen help** built (`web/src/lib/install.ts`, `screens/Install.tsx`): a dismissible card on Today for Android and iPhone browsers (one-tap Add where Chrome allows it, steps otherwise; English and Hindi), hidden for 14 days after "Not now", hidden once installed or on a desktop; plus an "Add to home screen" entry in the Family tab. Tested with an Android and an iPhone browser identity, and with "installed" simulated. NOT tested on a real phone.
- [ ] **Netlify badge** ("Powered by Netlify", bottom-right on the live site): it is a Netlify setting, not our code. Turn it off at Netlify > the site > Project configuration > General > Powered by Netlify badge (no redeploy needed). Netlify shows it on new Free-plan projects (created on or after 19 Aug 2026).
- [ ] **Real-phone check** of pull to refresh and the install card on Android Chrome and on an iPhone (Safari).

## One reminder per time slot (built 2026-10-15, see `docs/slot-reminders.md`)
- [ ] **Meta:** submit `carelink_dose_reminder_multi` in English and Hindi (wording in `docs/whatsapp-templates.md`, section 15) and wait for approval.
- [ ] **Roll out in order:** `db push` (0030) first, then push the API, then set `SLOT_REMINDERS_ENABLED=true` in Render after the template is approved. Test one real slot on your own number.
- [ ] The Today screen and the Pilot stats page still count doses one by one (they do not need to change). Check on a real phone that "Taken all" turns every dose of the slot to taken.
- Next big feature (designed, waiting): the escalation ladder with a nearby helper; it builds on slots. Decisions pending: parent consent for a helper, quiet hours behaviour, which plans, the expiring helper link, the "two unanswered in a row" rule and the 3-day silent stop.

## Escalation ladder (started 2026-10-15, see `docs/escalation.md`)
- [x] Step 1: database (`taken_late`, policies, escalations, critical flag, handle_escalation) and late-confirmation handling in the API, Today and stats. NOT on the live database yet: migrations 0030, 0031, 0032 must be pushed in order (`db push`), then the API and website.
- [x] Step 4 (screens) built 2026-10-15, NOT pushed. Safety net screen, critical toggle, progress card on Today.
- [ ] Phase 2 as listed in `docs/escalation.md`; alert-detail screen; templates 15 to 19 need Meta approval.
- [ ] Before switching any of it on for families: a reliable timer (paid hosting plus a warning when ticks stop), privacy text, and the "not an emergency service" wording.
- [x] Step 2 (server logic) built 2026-10-15; see `docs/escalation.md`. NOT pushed. To use it: submit the four new templates (sections 16 to 19 of `docs/whatsapp-templates.md`) and wait for approval, push migrations 0030 to 0032 (`db push`) and the API, set `ESCALATION_ENABLED=true`, then switch a parent's policy on. There is no screen for the policy yet (step 4); until then it is switched on in the database.

- [ ] `carelink_parent_checkin_nudge_v2` is Marketing at Meta (decided 2026-10-17). The Pilot stats page counts only consent requests as Marketing, so its cost estimate slightly undercounts check-in nudges (they are rare). Fix the count if nudges become common. Also confirm the other three new templates (dose nudge, parent replied, parent silent) are Active at Meta before switching escalation on.
