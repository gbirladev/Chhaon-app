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
- [x] **Email login sends a code, not a link (fixed 2026-11-03).** Supabase's Magic Link and Confirm signup templates were still the stock "click this link" ones on the live project, even though the local `supabase/config.toml` + `supabase/templates/otp.html` already send `{{ .Token }}`. Fixed by hand in the dashboard (Authentication > Email Templates) to match `otp.html`. Reminder: never run `supabase config push` to do this (local config carries test login codes).
- [x] **Custom SMTP configured 2026-11-03:** Gmail (`chhaon.app.mail@gmail.com`) via an App Password, `smtp.gmail.com:587`, set in Supabase Dashboard > Authentication > Settings > SMTP Settings. Fine for the pilot (~500 emails/day cap); move to a dedicated provider (SES, Brevo, Resend) if volume grows past that.

## Open question: Supabase DB moved from Mumbai to Sydney (found 2026-11-03)
`api-java/deploy/aws/INFRA.md` says the Supabase DB was moved to **Sydney (ap-southeast-2)** on 2026-11-02 to co-locate with the new AWS backend (the free-plan AWS account is region-locked to Sydney). This was done as part of the Java backend / AWS migration, without a corresponding decision recorded here. It contradicts every other doc (`CLAUDE.md`, this file, `docs/deploy.md`), which still say Mumbai, and `CLAUDE.md` ties the Mumbai choice specifically to **health data and the DPDP Act 2023** ("prefer servers in India"), not just latency.
- [ ] **Decide deliberately:** is hosting Indian residents' health data in Australia acceptable for the pilot, or does the DB need to move back to Mumbai? Not yet decided; flagged, not acted on.
- [ ] Once decided, update `CLAUDE.md`, `docs/deploy.md` and this file to match reality (they currently all say Mumbai).

## Reports tab and the login token (found and fixed 2026-11-05, branch `r3-facts`)
**Cause:** the Reports wallet (`web/src/lib/wallet.ts`) called Supabase directly (REST `documents`, `wallet_usage`, Storage
`reports`) with the login token our Java backend mints (`SupabaseCompatibleTokenService`: HS256, signed with `SUPABASE_JWT_SECRET`).
Supabase accepts that token only when the secret is the project's own legacy JWT secret and the project still trusts it; the Java API
accepts it anyway, which is why only Reports failed. Checked locally: a token signed with the local Supabase JWT secret gets 200, any
other secret gets REST **401 `PGRST301`** (Storage 400). The local demo backend therefore runs with a different `SUPABASE_JWT_SECRET`.
**Production (reasoned, not called):** the same happens unless the GitHub secret `SUPABASE_JWT_SECRET` is the **Sydney** project's
legacy secret (it may still be Mumbai's) and Sydney (which signs its own tokens with ES256) has not revoked that legacy key. Owner
check: Sydney dashboard -> Project Settings -> JWT Keys (legacy secret in use / previously used, same value as the GitHub secret?),
or open Reports / add a medicine photo in the live app.
**Fix:** the wallet now goes through the Java API (`api-java/.../wallet/`): records as the signed-in caregiver (`UserScopedSql`, so
RLS, the folder check and the plan/page/300 MB limits still apply); files through one-time signed upload addresses and 5-minute
signed links made by the backend with the service-role key (paths chosen by the backend, only that person's saved files; deleting a
report/page removes its files; unsaved uploads are discarded). If the backend's storage is not configured (503 `storage_off`) the
web falls back to the old direct Storage calls.
- [ ] **Owner setting (no new secret, already wired in backend-deploy.yml / send-ssm-deploy.sh / backend.env.example):** GitHub
  secrets `SUPABASE_URL` (`https://utjgegiioflwnmesmgcv.supabase.co`) and `SUPABASE_SERVICE_ROLE_KEY` (Sydney service_role or
  `sb_secret_` key), then Backend Deploy. Local demo backend: `SUPABASE_URL=http://host.docker.internal:54321`, the local
  service_role key, and `SUPABASE_JWT_SECRET` = the local "JWT secret" from `npx supabase status`.
- [ ] **Still direct (same risk):** medicine photos (upload/preview/remove in `App.tsx`) and feedback pictures (`lib/feedback.ts`).
  Move them the same way if the production check fails.
- [ ] Optional one-off: medicines with stock 0 that were never counted -> "not tracked": `supabase/maintenance/optional_untrack_zero_stock.sql`
  (preview first; not run).

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

- 2026-10-17: all four new Meta templates are created (16 `carelink_parent_dose_nudge`, 17 `carelink_parent_checkin_nudge_v2` = Marketing, 18 `carelink_alert_parent_replied`, 19 `carelink_alert_parent_silent` = Utility, accepted in both languages). Before switching escalation on: confirm 16, 17 and 18 show Active in en AND hi, and send one real test of each.

## To test before switching escalation on (kept for later, 2026-10-17)
- [ ] In WhatsApp Manager confirm these show **Active** in English AND Hindi: `carelink_parent_dose_nudge` (Utility), `carelink_parent_checkin_nudge_v2` (Marketing), `carelink_alert_parent_replied` (Utility); `carelink_alert_parent_silent` (Utility) is already accepted.
- [ ] Do one real send of each with a throwaway parent number and your own caregiver number: dose nudge, check-in nudge, "parent replied" alert, "parent silent" alert. Check the wording, the buttons (Taken / I'm okay / Call me), the language, and that a tap on a nudge button is recorded.
- [ ] Order to switch on: `db push` (migrations 0030 to 0035) -> deploy the API -> set `ESCALATION_ENABLED=true` and `SLOT_REMINDERS_ENABLED=true` in Render -> switch on one parent's Safety net.
- [ ] Love loop (needs no template): after the same push, set `LOVE_LOOP_ENABLED=true`, turn it on for one parent, tap a heart, and check that the message arrives on a real phone in the open window, that NO HEARTS works, and that delivered/read statuses show up.
- [ ] Hindi text of the love messages and the new screens: native-speaker review.

## "Me" profile (2026-10-17, see docs/self-profile.md)
- [ ] Submit template **`carelink_self_optin`** to Meta (Marketing, en + hi, Yes/No buttons; text in docs/whatsapp-templates.md section 20). Until it is approved a real own-number confirmation cannot be completed.
- [ ] After approval: add yourself on a real phone, tap Yes, check the 08:00 reminder arrives and Taken works.
- [ ] Pilot stats count own profiles as parents (admin_stats not changed).
- [ ] Push order now: migrations 0030 to 0036 (0037+ if added), API, web.

- 2026-10-17: new Meta templates (`carelink_self_optin` and the other newly added ones) are **under review** at Meta. Check again later: all must be Active in en AND hi before switching escalation on / using "Me" with a real number.

## WhatsApp delivery problems found 2026-10-29 (live)
- [ ] **132018 on the Hindi photo reminder** (`carelink_dose_reminder_photo`, hi): the Hindi version has NO image header, the app sends one, so Meta refuses every send. Fix at Meta: add an Image header with a sample image to the Hindi version (or create `_v2` in en + hi with the header and tell Claude the name). Check the English version too. Until then the hotfix (commits df6784f and 68ac7d4, on GitHub: checked 2026-11-02) sends the plain reminder when Meta refuses the photo one or the template is not usable.
- [ ] **131049 "healthy ecosystem engagement"** (Meta chose not to deliver): hit consent requests (Marketing) and the daily 08:00 IST message (probably the check-in). Meta moved a Hindi template to Marketing ("did not meet utility guidelines", 2026-10-29). **Review requested by the owner**, available until 2026-12-13. Record which template and the outcome. If it stays Marketing: option B = send the daily check-in as a free-form message with buttons when the 24-hour window is open (after a Taken tap), template only as fallback.
- [ ] Build: log every message the app sends (template, parent, wamid) so every failure is matched, shown to the family and attributed to a template. Today failed check-ins, consent requests and alerts are silent (only doses are matched).
- [ ] Build: parent-started chat (link / QR with a ready "Hi" to the business number) so consent can be asked inside the 24-hour window without a Marketing template.
- [ ] The 2 parents still waiting for consent: ask them to send "Hi" to the business number first, then press Send again.
- [ ] Check categories of `carelink_daily_checkin`, `carelink_dose_reminder`, `carelink_dose_reminder_photo` and the alert templates in WhatsApp Manager (Insights: Sent vs Delivered per template).

## Upcoming features: the Premium plan (discussed 2026-11-02)
Reminders stay free: nobody pays for them. Premium sells one promise, not a list of features:
**"A real person watches over Papa's health every week, and tells you before a small problem becomes a big one."**
A named care coordinator per family ("Chhaon Saathi"), helped by AI. Indian families pay for people and results (Emoha Rs 1,999–10,999/month,
Anvayaa USD 999+/year), not for apps; software-only competitors (Maitri Rs 299) cannot offer this. Working price: **Rs 499/month** (test Rs 499 vs Rs 999).
NOT decided yet: it is a service, and CLAUDE.md keeps services out of v1. The 3-week test below decides.

- [ ] **Test first, no code (3 weeks):** the owner is the coordinator for 5–10 pilot families who agree. Each week: call each parent (how they feel,
      BP/sugar readings, medicine problems, needs), log readings in a private sheet, send the child a Sunday summary, act on alerts. After 3 weeks, offer
      to continue at Rs 499/month. **Success = 3 or more of 10 families actually pay.** Note the minutes spent per family (tells how many families one
      coordinator can handle; the estimate is about 300).
- [ ] Before the test: the weekly call script for the parent and the Sunday summary for the child (English + Hindi), and consent text that says a
      person reviews the data.
- What Premium would include (build only if the test passes):
  - [ ] **Coordinator:** the same person every week (name and photo in the app), a weekly call in the parent's language, a summary to the family,
        acts when something is off (calls the parent, then tells the family what happened and what to do), guides the family in a crisis
        (hospital, insurance card, documents). Not an emergency service: 112 first.
  - [ ] **Health Control ("Is the medicine working?"):** the parent sends a photo of the BP machine or glucometer screen; Chhaon reads it and asks the
        parent to confirm (✅ / ✏️); trend next to the medicine confirmations; the target is the one the doctor gave (typed in by the family), never our
        own "normal"; alert when readings stay above it. Why: in India only about 15% of people with high BP have it under control (WHO 2023), and only
        36% of people with diabetes have their sugar under control (ICMR-INDIAB 2022). Lawyer check before charging (CDSCO).
  - [ ] **Doctor report** before each appointment (readings, missed doses, current medicines), and **prescription photo → medicine plan update**
        after the visit (the child approves every change).
  - [ ] **Coordinator dashboard** (internal): families needing attention today (sorted by AI), call notes, AI-drafted summaries. Staff see only
        their own families; the family can see who opened what.
  - [ ] **Care Log** for families with a paid attendant: arrival, medicines given (photo), meals; alert when something is missing. Ask pilot families
        with an attendant before building.
  - The **Safety net** (built, switched off) becomes part of Premium, because WhatsApp alerts cost money. In-app alerts stay free for everyone.
- Rules: coordination, never medical advice (no medicine changes; the doctor decides). Fraud-safe: the coordinator never asks for money, OTP, Aadhaar
  or bank details, calls only from Chhaon's number, and uses a family code word; background checks for staff.
  - [ ] Add to the `consent_yes` reply (`api/app/whatsapp/templates.py`): "Chhaon will never ask you for money, OTP, Aadhaar or bank details. If anyone
        does, it is not us." (free-form reply, no Meta approval needed; Hindi needs a native read).
- Free plan change to consider: cover all of one parent's medicines (today Free allows 2) once one-reminder-per-slot is live, because the cost then
  depends on time slots, not on the number of medicines.
- Rough maths, to be measured in the test: 300 families x Rs 499 = about Rs 1.5 lakh a month per coordinator, against a salary (about Rs 30,000 is an
  assumption) plus WhatsApp and AI costs.
- Later: AI voice calls take over the routine weekly calls and coordinators handle only problems (South Korea's Naver CareCall does AI check-in calls);
  partner money (refills when stock runs low, home lab tests when due); employers and group insurers paying for employees' parents; an NRI plan in dollars.

## Growth plan: doctors bring families, vendors pay (decided 2026-11-03)
Basic reminders will not earn from families. Plan: grow the user base through doctors, keep Chhaon free for families and doctors, and earn
from vendors (pharmacies, labs, devices) when a family chooses to order something.
- **Rule that protects the doctors:** a doctor gets nothing commercial for recommending Chhaon: no money, gifts, free promotion or ranking.
  Regulation 6.4.1 of the 2002 Medical Council ethics code (in force; NMC put its 2023 rules on hold) bars doctors from any "gift, gratuity,
  commission or bonus" for referring patients, labs included. "We'll market your clinic free in return" was dropped for this reason.
  The doctor recommends Chhaon only because it helps their patients.
- [x] **Doctor kit (built 2026-11-03):** `chhaon.site/doctors` (`web/public/doctors.html`): what Chhaon is, the "nothing in return" note,
  and printable QR cards (English + Hindi, 6 per A4 sheet, optional clinic name). Linked from the home page footer.
  Each doctor gets their own link `chhaon.site/doctors?ref=D001`; the printed QR then opens `chhaon.site/app/?ref=D001`, so cards printed now
  keep working when counting starts. Keep the list of codes (doctor, clinic, date) in a private sheet.
- [ ] **Count families per doctor (needs the new backend):** save `ref` from the app URL on sign-up. Not built: sign-up is moving to the Java
  backend's own login, so agree with whoever owns `api-java/` where the field lives (a `profiles.referred_by` column) before building it.
  Families who arrive without a code (home page, typed address, a friend's message) are counted as "direct"; nothing about the app changes for
  them. To see every channel, add one optional sign-up question: "How did you hear about Chhaon? Doctor / Friend or family / WhatsApp / Google /
  Other", saved next to the code.
- [ ] **Start with 10 doctors met in person,** not a mass mailing. Measure: patients per doctor, families still active after 4 weeks.
- [ ] **Vendor income, easiest first:** (1) BP machine / glucometer affiliate links in the child's app (supports Health Control);
  (2) refill orders through one partner pharmacy (commission); (3) home lab tests when due (referral fee); (4) insurance later, through an
  IRDAI-licensed partner; (5) no pharma ads. Paid placements are labelled; the family can always keep their own chemist; data is never sold or
  shared with vendors (DPDP: earn only when the family itself orders). Offers and payments only in the child's app, never on the parent's WhatsApp.
- [ ] **Coming later for doctors, only with the family's consent:** a one-page summary of missed doses before each visit.
- Note: `web/public/about.html` still says the old name "Vatsalaya"; update or remove it.
- [x] **Doctor summary, family side (built 2026-11-03):** Family > Share with doctor (`web/src/screens/DoctorSummary.tsx`, `web/src/lib/adherence.ts`).
  One page: patient details, medicines now, doses confirmed in the last 30 days (total and per medicine), check-ins; English by default, Hindi
  one tap away; print / save as PDF and share as text. Counts only results (taken, taken late = confirmed; missed = no confirmation; pending,
  sent and skipped are left out), never judges, says "not medical advice", never shows 100% while a dose is unconfirmed. Hindi wording
  (`doctorSummary` in `hi.json`) needs a native read.
  - [x] **"Prepared for" line (2026-11-05):** two optional fields (doctor's name, clinic or hospital) on the summary screen; shown as "Prepared for:
        Dr <name>, <clinic>" at the top of the sheet, in the print/PDF and in the shared text (Hindi: "किसके लिए: डॉ. ..."). Kept per parent on this
        device only (localStorage, cleared on sign-out); the first doctor in the parent's `doctor_contacts` is the starting value. No backend change.
        Nothing about the doctor is shown to anyone else (Regulation 6.4.1). QR cards: a typed clinic name prints on all 6 cards as
        "<clinic> / For our patients · हमारे मरीज़ों के लिए".
- **Parked (decided 2026-11-03): no doctor login for now.** It would make a stranger a viewer of a parent's health data (needs NMC registration
  checks, per-patient consent from the family, an audit log, revoke), doctors rarely adopt another app, the backend login is being moved to the
  Java service, and no doctor has asked yet. The family carries the sheet to the visit instead.
  - [ ] **Next, only if families use the sheet and doctors ask to receive it:** an expiring read-only link (about 7 days, family can cancel, no
        phone numbers or address on the page, wording "anyone with this link can see this page"), modelled on the emergency link. Needs a backend
        part: agree it with whoever owns `api-java/` first.
  - [ ] **Only after that, if 10 or more doctors actively ask:** a doctor login, with NMC verification, per-patient consent, audit log, and a
        lawyer's view. Nothing commercial may flow to the doctor (Regulation 6.4.1).
  - [ ] **Find out first:** give the QR cards to 10 doctors in person; ask the families whether they showed the summary and whether the doctor
        wanted more.
- [ ] **Medicine photos not showing (reported 2026-11-03):** the Medicines list never shows photos (by design, only the medicine detail screen
  does; the photo flow itself works locally: upload, signed link, image shown). Two things to settle: (1) if thumbnails on the list rows are
  wanted, add them (one signed link per photo; changes the list design); (2) on the live site, check the Sydney Supabase project's Storage >
  `medicine-photos` (and `reports`): files do not move with a database copy, so photos and wallet files from the old Mumbai project may be
  missing while medicines still point at their paths. If empty, copy the files across from the old project.

## Production after the move to AWS: what is broken, and what "enable Safety net / Love in production" needs (found 2026-11-03)
The live site (`chhaon.site/app`) calls `https://api.chhaon.site` for five things. That address is now the **Java backend** on the AWS box
(54.79.216.98), which does not have them: they lived only in the Python API on Render, which is no longer used. The Java backend has only auth,
recipients, medicines and `admin/tick`; the web app itself reads the database through Supabase directly.
- [x] **Emergency card public page was broken (checked in a browser 2026-11-03 with a made-up link); fixed and live:** `chhaon.site/app/#/e/<token>` shows "Could not
      load. Please check your internet and try again." for every link, because `GET /public/emergency/<token>` is not served. This is what a stranger
      opens in a crisis, so fix it first.
      **FIXED AND LIVE 2026-11-03** (migration 0038 + web deploy; real link verified): see "Done 2026-11-03" below.
- [ ] **Asking a parent for consent** (`POST /recipients/{id}/consent-request`) and **WhatsApp alerts opt-in** (`POST /alerts/whatsapp/request`) are not
      served, so new families probably cannot start reminders from the app. Not tested with a live login.
- [ ] **`GET /features` is refused**, so the Safety net and Love messages screens stay hidden in production whatever the settings are.
- [ ] **Reminders may not be sending at all:** the Java scheduler is OFF by default (`chhaon.scheduler.enabled`, "enabled at cutover") and the deploy
      workflow's server settings do not turn it on. Could not see the server itself: check `/etc/chhaon/backend.env` on the box.
- [ ] **Not yet ported to Java** (per `api-java/README.md`): escalation ladder (Safety net), love loop, one-reminder-per-slot, appointment reminders, alert
      delivery to the family's WhatsApp, and handling the parent's "Taken" / check-in replies (the WhatsApp webhook). Reports and AI, family invites,
      emergency links, plans and notifications are also still on the Python side.
- **Do NOT make the web show the Safety net by faking `/features`:** families would see a safety net that sends nothing. It must run for real first.
- [ ] **Decide the path with whoever owns `api-java/`:** (A) run the Python API again next to the Java one (the web app calls only Python routes on this
      address, and the Safety net, love loop, consent and emergency page are already built and tested there: 282 API tests); (B) port them to Java feature
      by feature (the buddy's plan; slower); (C) agree the order first. Whichever it is, the five routes above come before any new feature.
- [ ] **To switch the Safety net / Love messages on once the code runs somewhere:** migrations 0030 to 0037 on the Sydney database (not verified), the
      server settings `ESCALATION_ENABLED`, `LOVE_LOOP_ENABLED`, `SLOT_REMINDERS_ENABLED` on whichever server runs that code, a scheduler tick that really
      runs and a warning when it stops, then the real send tests with throwaway numbers listed under "To test before switching escalation on", then one parent
      first. The templates list no longer matters if sending now goes through the WhatsApp gateway: confirm how the gateway handles consent, STOP and buttons.
- Locally both are already ON (`api/.env` has `ESCALATION_ENABLED=true` and `LOVE_LOOP_ENABLED=true`; Family tab shows Safety net and Love messages).
  `SLOT_REMINDERS_ENABLED` is not set locally. AI report reading stays OFF on purpose (needs doctor and lawyer review). Admin screens (Pilot stats) show
  only for admin logins, nothing to change.

## Before the first 10 doctors get QR cards (listed 2026-11-03)
- [x] **Free plan limits (solved by migration 0039 once it is applied: everyone is on Family):** after the 30-day trial, Free allows only 2 medicines per parent and no family invites, so a doctor's patient on 5 medicines would
      have most reminders paused. Raise the Free limits or put pilot families on a bigger plan (`docs/plans.md`, "Change a limit").
- [ ] **Supabase Auth Site URL and redirect URL** should be `https://chhaon.site/app` (dashboard setting), or login and sign-up confirmation land on the
      marketing page.
- [ ] **Doctors page promises to confirm:** "if that ever changes, [families] will see the price before they pay"; "we show no advertisements" (matches the
      privacy policy but conflicts with the planned vendor links: reconcile before building those); no claim about where data is stored (Mumbai or Sydney is
      undecided).
- [ ] **"About us / who is behind Chhaon"** for the doctors page, once the company details exist. Doctors will ask.
- [ ] **Native Hindi read** of the doctors-page script and the `doctorSummary` texts.
- [x] **Home page links to the doctors page** (2026-11-03): "For doctors" in the top bar on desktop, and a line under the main buttons that also shows on
      phones (the top-bar links are hidden on screens narrower than 960 px), plus the footer link.

## Done 2026-11-03 (in the code, committed): emergency card from the database, pilot plan setup, doctors page
**LIVE since 2026-11-03:** migrations 0038 and 0039 were applied to the Sydney database by the owner, the web was deployed, and the emergency card was opened with a real link in a private window and shows the information.
- **Emergency card without the old API.** `supabase/migrations/20261103000038_public_emergency_card.sql` adds `public_emergency_card(p_token)`: the same minimal
  card the Python route returned (first name, age, blood group, conditions, allergies, active medicines with times, up to 4 contacts, first doctor), `null`
  for unknown, revoked, deleted or malformed links, hidden characters cleaned, every view counted, callable by anon, the table itself still closed to anon.
  The public page (`web/src/lib/emergencyPublic.ts`) now calls it with a plain request (no Supabase library, no login session). Checked: identical output to the
  Python route for all 19 local parents (including the fuzz-test records); 40 database tests (`supabase/tests/public_emergency_card.test.sql`); 20 browser checks
  with the old API blocked (real card, capitals, unknown / malformed / revoked links, 429, 500, offline, Hindi, only the small page code loads).
  - [ ] Not replaced: the Python route's per-IP rate limit (30 a minute). A 256-bit token cannot be guessed; if guessing traffic ever shows up, limit at the edge.
  - [ ] When the Java backend takes over the other four routes, it can expose this function too; nothing breaks if it does.
- **Pilot plan setup.** `supabase/migrations/20261103000039_everyone_on_family.sql`: new accounts start on Family, existing Free and Plus accounts move to Family,
  **admin accounts are always Ultra** (data move plus a trigger, `profiles_set_admin_ultra`). Family = 4 parents, 10 people in a family, 20 medicines per parent,
  100 reports per parent. The Plans screen (Family tab, "Your plan") shows only the account's own plan and a "Pilot version" note whose numbers are read from the
  `plans` table; `VITE_PLANS_ENABLED=true` brings the full view back. Details and how to go back: `docs/plans.md`, "Plans off". Tests: `plans_default_family`
  (17), and `plans` / `documents_limits` now set their Free accounts explicitly. All 17 database test files pass (368 checks).
  - [ ] Hindi wording of the pilot note and the "Your plan" link needs a native read.
  - [ ] The doctors page repeats the pilot limits as plain text: change it if the Family limits change.
- **Doctors page:** the benefits are now the first thing on the page, highlighted, in two columns (for the patient's care, for the practice); the page says plainly that
  nothing is offered to doctors for recommending Chhaon (Regulation 6.4.1). Pilot limits stated under "At a glance" and in the cost answer.
- [ ] **To make it live (needs your database and GitHub access):**
  1. **The Sydney database (project `utjgegiioflwnmesmgcv`, checked 2026-11-03) has NO migration history table** (`supabase_migrations.schema_migrations` does not
     exist), so the Supabase tool cannot know what is applied: **never run `supabase db push` against it** (it would try to apply every migration from the start).
     Apply changes by pasting SQL into the SQL editor. A schema check on that day showed the tables and columns of migrations 0018, 0022, 0027, 0028, 0029, 0032, 0033,
     0034 and 0036 all present, so 0038 and 0039 can be applied as they are. Paste `20261103000038_public_emergency_card.sql`, run it, then
     `20261103000039_everyone_on_family.sql`, run it.
     - After 0038: `select public.public_emergency_card(repeat('0', 64)) is null, has_function_privilege('anon','public.public_emergency_card(text)','execute'), has_function_privilege('anon','public.record_emergency_view(uuid)','execute'), has_function_privilege('anon','public.card_clean(text, integer)','execute');` should give `true, true, false, false`.
     - After 0039: `select plan, case when is_admin then 'admin' else 'user' end as kind, count(*) from public.profiles group by 1,2 order by 1,2;` should show no `free` or `plus` rows and the admin on `ultra`; `select column_default from information_schema.columns where table_schema='public' and table_name='profiles' and column_name='plan';` should show `'family'::text`.
     - To undo 0038: `drop function public.public_emergency_card(text); drop function public.card_clean(text, integer);`. To undo 0039: see `docs/plans.md`, "Plans off".
  2. Push to `main` so GitHub Actions deploys the web (it only runs when `web/` changes).
  3. Open `https://chhaon.site/app/#/e/<a real link>`: it should show the card. Then Family > Your plan.
  4. Optionally set the GitHub repository variable `VITE_PLANS_ENABLED` (leave unset or `false` for the pilot).


## Decided NOT to build: "nearest / top recommended doctors" (2026-11-03)
Reasons: (1) it contradicts the promise on the doctors page ("we do not ... promote clinics") and would give a doctor who recommends Chhaon something in return
(Regulation 6.4.1 of the Medical Council ethics code; paid rankings are also restricted); (2) rankings invite pressure and need an objective, defensible method
(reviews, moderation, upkeep); (3) a wrong or outdated recommendation becomes Chhaon's liability; (4) Google Maps and Practo already do this better and free.
- [ ] **Instead, if wanted:** (a) a plain link on the appointments screen that opens a Google Maps or Practo search for the specialty in the parent's city (no ranking,
      no recommendation); (b) a **"Your doctors"** list the family fills in (name, clinic, phone, address): one tap to call or get directions, shown on the emergency card.
- [ ] Revisit a real directory only after the pilot, through a partnership with an established directory, and with a lawyer's view first.

## Permissions on the Sydney database differed from the migrations (found 2026-11-03): functions AND tables FIXED and verified
Testing the live card function through the public API showed that an anonymous visitor could run `record_emergency_view`; listing everything an anonymous visitor could
run showed **48 functions where the migrations allow 4**. Five server-only functions (`erase_caregiver`, `claim_consent_request`, `confirm_dose_slot`, `consume_stock`,
`generate_dose_events`) run with full power and do not check who calls them, so they were open to anyone holding the site's public key (a secret ID was the only protection).
The GRANT / REVOKE statements were lost when the database was copied to Sydney.
- [x] **Repaired and verified 2026-11-03:** `supabase/maintenance/restore_function_permissions.sql` (generated from the correct local permissions; tested on a deliberately opened
      local copy: 0 differences, safe to run twice). After running it on Sydney the totals were exactly the expected `4 | 40 | 51 | 51` (anonymous | signed-in | server | all), and from
      outside the six server-only functions answer "permission denied" while `public_emergency_card` still answers visitors.
- [x] **Table permissions checked 2026-11-03: ALSO lost.** Every table on Sydney had full permissions for anonymous and signed-in users (the migrations restrict writes on several: `emergency_links`,
      `care_circle_members`, `notifications`, `appointments`, `reports`); only row security (RLS) was protecting them. Two tables that are not in the migrations, `user_credentials` (Java login,
      empty at the time) and `flyway_schema_history` (2 rows), had row security OFF and were readable and writable by an anonymous visitor (count-only requests confirmed it; no rows were read).
- [x] **Done 2026-11-03: the two Java-backend tables are locked (from outside a count-only request now gets 401, not 200)** (the Java service connects as `postgres`, per `api-java/README.md`, so it is not affected; confirm with its owner):
      `revoke all on table public.user_credentials from public, anon, authenticated; alter table public.user_credentials enable row level security; revoke all on table public.flyway_schema_history from public, anon, authenticated; alter table public.flyway_schema_history enable row level security;`
      Check from outside: a count-only request to `/rest/v1/user_credentials` with the public key must now be refused, not `200`.
- [x] **Done and verified 2026-11-03: table permissions restored (the 26 tables match the correct state line for line, 0 differences; from outside, `profiles`, `care_recipients`, `emergency_links` show 0 rows and `plans` is refused):** run `supabase/maintenance/restore_table_permissions.sql` on Sydney (generated from the correct local permissions; tested on a deliberately opened local copy: 0 missing,
      0 extra, identical 26-line fingerprint, safe to run twice), then run `supabase/maintenance/table_permissions_check.sql` and compare with its 26 expected lines (the two Java tables should show `0 | 0 | 0 | f | t`).
- [ ] **Optional hardening, test locally first:** the anonymous role has no need for any table in this app (the public card is a function). Revoking all table permissions from `anon` would be a second lock.
- [ ] **Tell whoever copied the database** to keep the permissions next time (for example `pg_dump` without `--no-acl`), and to run the two maintenance files after any future copy.
- [ ] **New functions:** Supabase gives every new function to anonymous, signed-in and server roles by default; a migration that adds a server-only function must revoke it, and
      `restore_function_permissions.sql` must be regenerated to include it.

## Reported in production, 2026-11-03 (evening)
- **Time shows Sydney / "morning" at night in India.** Today's greeting and clock use the parent's STORED time zone. Reproduced in a browser: with `Australia/Sydney` stored, Today says GOOD MORNING
  while it is night in India. No code anywhere sets Sydney as a default (the defaults are `Asia/Kolkata`); Sydney only comes from data. Also, a wrong stored zone could not be corrected: the profile
  form hid the time zone box for +91 numbers and saved the stored zone unchanged. **Fixed in code (not live until pushed):** the box is always shown, starts at the stored zone (or the phone's country),
  and what is saved is what it shows (11 browser checks).
  - [ ] **Urgent for real families, because reminders go out on the parent's clock:** on Sydney run `select left(whatsapp_phone, 3) as phone, timezone, count(*) from public.care_recipients group by 1, 2 order by 3 desc;`
        and `select timezone, count(*) from public.profiles group by 1;` and `show timezone;`. Parents with a +91 number and a non-India zone are wrong: fix them with an UPDATE after previewing
        (`update public.care_recipients set timezone = 'Asia/Kolkata' where left(whatsapp_phone, 3) = '+91' and timezone <> 'Asia/Kolkata';`). The trigger clears pending doses when the zone changes, so
        reminders are regenerated on the right clock. Find out where Sydney came from (the copy of the data? a script?) so it does not happen again.
- **Phone still shows the previous view.** Reproduced: a browser that visited the old site shows the OLD app on the first visit after a deploy and the NEW app on the next visit (the offline copy in the
  service worker is replaced in the background). Nothing is broken; open the app twice. One real problem found: an icon added to the home screen while the app lived at `/` opens `/`, which is now the
  website. **Fixed in `web/public/home.html`:** opened as an installed app (Android standalone, iPhone home screen), `/` goes straight to `/app/`. 5 browser checks.
  - [ ] Optional: a "new version, tap to refresh" banner when the service worker updates (not an automatic reload: it could lose typed text).
- **Report upload fails and medicine photos are missing in production.** The files live in Supabase Storage (three private buckets: medicine-photos, reports, feedback), which a database copy does not bring
  over. Unverified until checked on Sydney.
  - [x] **Storage check run on Sydney 2026-11-04: it returned NO ROWS = the project has no storage buckets and no storage access rules at all.** That is why report uploads fail (the bucket does not exist) and old photos do not show.
  - [x] (Done 2026-11-04: script ran on Sydney, outside probe showed all 3 buckets exist and refuse visitors, user confirmed report and medicine photo uploads work in the live app.) Run `supabase/maintenance/restore_storage.sql` on Sydney (creates the three private buckets and the 7 access rules; the four functions the rules use already exist there), then run `storage_check.sql` again: expect 3 buckets (0 files) and 7 rules. Then add a report photo and a medicine photo in the app.
  - [ ] **Copy the old files** with `supabase/maintenance/copy_storage.mjs` (tested 2026-11-04 against 861 real local files with a stand-in new project: look-only by default, never overwrites or deletes, retries, refuses old = new, checks the result; byte-for-byte identical, 11 checks).
        Needs the OLD project to still exist: do not delete it first. In your own terminal set `OLD_SUPABASE_URL`, `OLD_SERVICE_KEY`, `NEW_SUPABASE_URL` (`https://utjgegiioflwnmesmgcv.supabase.co`), `NEW_SERVICE_KEY` (Dashboard, Project Settings, API, the secret / service_role key of each project; never paste them into a chat or a file in the repository),
        then `node supabase/maintenance/copy_storage.mjs` (look only) and `node supabase/maintenance/copy_storage.mjs --apply`.
  - [ ] If a bucket or rule is missing: run `supabase/maintenance/restore_storage.sql` (tested on the correct local database: 0 differences, no file touched). That restores uploads for NEW files.
  - [ ] **UPDATE 2026-11-04: the user thinks the old Mumbai project was already deleted. Waiting for the buddy to say whether he copied the storage files before the move. If not, old photos are gone: families re-upload; optional read-only query to list rows whose file is missing, and check the app shows a placeholder.** Original note: **The old photos and reports are only in the old (Mumbai) project's storage: do NOT delete that project until they are copied** (a script using both projects' service keys; never paste keys in chat).
        Rows in the database already point at the right paths, so copying the files with the same names is enough.
- **"Ask for location access" (decided not to, 2026-11-03).** The device location is the CAREGIVER's, who is often in another country: useless for the parent's time zone (the box and the phone's country do that)
  and for "doctors near Papa" (the parent's city does that). The one place location helps is a bystander on the emergency card page; there, a plain link to Google Maps ("hospitals near me") lets Maps
  ask for the location itself, so Chhaon never collects it. If ever added: only on a button tap, once, never stored, privacy text updated (DPDP), and `Permissions-Policy: geolocation=(self)`
  (today the header says `geolocation=()` in `web/vite.config.ts` and `docs/deploy.md`; GitHub Pages cannot set headers).
- **Dark / light mode:** an agent was launched on 2026-11-03 in its own git worktree; review its branch and merge when it reports.

## URGENT: reminders stopped after the move to AWS (found 2026-11-03)
**Evidence (Sydney database, read-only query):** the last reminder ever sent was 2026-11-02 15:30 UTC (21:00 India time); 13 doses since then are still `pending` long after their time; the Today screen therefore shows
"NEXT 9:30 AM" at 11:55 PM (it decides what is next from the saved status only). The Java backend itself is up (`/actuator/health` answers 200), so the web side is alive but **nothing sends reminders**.

**How it worked before (Python, Render):** a timer OUTSIDE the app (a Render cron job, woken by cron-job.org) called `POST /cron/tick` on the API every 1 to 5 minutes with a secret. One tick: create doses 24 h ahead, send due
reminders through Meta's WhatsApp templates, expire unsent ones, mark missed after 60 min, daily check-ins, missed check-ins, appointment reminders (and escalation, love loop and slot grouping when switched on). Parents'
"Taken", check-in, consent and STOP replies arrived at `/webhooks/whatsapp`.

**How it works now (Java, AWS):** the timer is INSIDE the Java program (`@Scheduled`, every 5 minutes, first run 1 minute after start), but it only does anything when `chhaon.scheduler.enabled=true`. The default is `false`
and the deploy workflow never sets it, so no tick has run. Sending goes through the self-hosted WhatsApp gateway (WA-AKG); with `WHATSAPP_GATEWAY_URL` blank it only logs ("dry-run") and returns a fake message id.
The Java tick has 6 steps (create doses, send due, unsent to skipped, missed, check-ins sent, check-ins missed). **Not in Java yet:** reading the parent's replies (no webhook: "Taken", check-in answers, consent Yes/No, STOP),
sending the consent request, appointment reminders, escalation (Safety net), love loop, slot grouping, WhatsApp alerts to the family.

**Two dangers of just switching it on:**
1. With the gateway not configured, doses would be marked `sent` with fake ids although nothing was delivered (it would hide the failure).
2. Without the reply webhook, a parent's "Taken" tap is never recorded, so after 60 minutes every dose becomes `missed` and the family gets a false "missed dose" alert.

- [x] **Decided 2026-11-04: Option B (the Java backend is the one backend; finish it before enabling its scheduler).** Plan below. (The options were: A) bring the Python tick back; B) finish the Java port first.)
      with tests; or (B) finish the Java port (reply webhook, consent request, appointment reminders) BEFORE enabling `chhaon.scheduler.enabled`. Do not enable the Java scheduler with only the current 6 steps.
- [ ] Confirm the gateway: `WHATSAPP_GATEWAY_URL` / `_TOKEN` / session set and the number connected; send one real test.
- [ ] When any scheduler resumes, old `pending` doses will be handled by the normal rules (unsent past the window become `skipped` and the family gets "reminder not sent, please call").
- [ ] Tell the pilot families that reminders were paused since 2 Nov 21:00 IST, if real families are using it.
- [ ] Build the Today warning ("reminders are delayed, please remind {Papa} yourself") and the scheduler heartbeat (already listed above) so this can never be silent again.
- **Plan for Option B (nothing below deploys until approved; Java work goes on a branch, never straight to main, because a push to `api-java/**` deploys to production):**
  0. [x] DONE 2026-11-04 (committed locally, not pushed): the red "Reminders are delayed" card on Today (a dose of a parent who has agreed, still `pending` more than 20 minutes after its time; English and Hindi; 12 browser checks).
         **Its limit:** it only helps while today's doses exist. If the scheduler is down at the start of a day, no doses are created, so Today just says "No doses are scheduled today" and the card cannot appear.
         The real fix is a **scheduler heartbeat**: each tick records `last_tick_at` in a small table readable by signed-in users, and the app shows the same card when it is older than about 15 minutes while reminders are expected.
         [x] Heartbeat, DB and web side DONE 2026-11-04 (committed locally): migration `20261104000040_scheduler_heartbeat.sql` (a one-row table nobody can read directly, `touch_scheduler_heartbeat()` for the server only,
             `scheduler_health()` for signed-in users; 11 database checks) and the Today card "Reminders have stopped" when the last tick is more than 15 minutes old ("2 minutes ago" style text in English and Hindi,
             silent when no heartbeat was ever recorded so there is no false alarm; 13 browser checks).
         [ ] Java side: at the end of each tick call `select public.touch_scheduler_heartbeat()` (best effort: catch and log, never fail the tick). Requested from the Java agent.
         [ ] To make it live, IN THIS ORDER: apply migration 0040 on Sydney (SQL editor), deploy the Java line, then the web. Until the first tick is recorded the app stays silent.
  1. [ ] Test foundation for Java (there are NO tests today): tests run against a real Postgres built from `supabase/migrations` (the SQL functions are the logic), in CI and locally; Maven runs through Docker (no JDK on the Mac).
         First, tests that pin down what the 6-step tick does today.
  2. [ ] The reply pipeline (the blocker): a signed webhook endpoint for the gateway (`message.received`, `message.status`; HMAC `X-Webhook-Signature`), idempotent, understanding free-text replies in English and Hindi (no buttons exist):
         Taken (link it to the reminder the parent replied to, else the latest one sent), check-in answers, consent yes/no, STOP and START. Port from `api/app/services/inbound.py` using the existing database functions.
  3. [ ] Messages without buttons: reminder, check-in and consent texts in English and Hindi that tell the parent how to reply; the three routes the web app already calls (`/recipients/{id}/consent-request`, `/alerts/whatsapp/request`, `/features`).
  4. [ ] The rest of the tick: appointment reminders, alerts to the family on WhatsApp; later the Safety net and love messages.
  5. [ ] Switching on safely: gateway sends a real test message; dry run; the scheduler for ONE test parent (scoped tick), then all; the heartbeat. `chhaon.scheduler.enabled` stays false until steps 2 and 3 are tested.


## Option B progress: Java reply pipeline merged into local main (2026-11-04, NOT pushed, NOT deployed)
Details: `docs/java-whatsapp-replies.md`, tests: `docs/java-tests.md`. (The "URGENT" and "Plan for Option B" sections live in the main checkout's uncommitted TODO; tick items 1, 2 and the consent route of 3 there.)
- [x] 1. Java test foundation (throwaway database `chhaon_java_test`, Docker Maven, tick characterization tests).
- [x] 2. Signed webhook `/webhooks/whatsapp-gateway`, typed-reply classifier (EN/HI/Hinglish), router, handlers (Taken, check-in, consent yes/no, STOP/START), idempotent.
- [x] 3 (part). Plain-text reminder / check-in / consent texts that say how to reply; `GET /features`; `POST /recipients/{id}/consent-request`.
- [ ] **Still to port (skipped on purpose to restore reminders first):** appointment reminders; WhatsApp alerts to the family (outbox, `/alerts/whatsapp/request`, the caregiver's confirmation); escalation; love loop. Until the alerts are ported the family sees missed doses only in the app.
- [ ] Hindi wording of all new messages needs a native review. Real-phone test of the swipe-reply (quote) path. LID-only senders are ignored (their STOP too): see the doc.

**PARKED FOR THE BUDDY (2026-11-04):** the product owner has no AWS or gateway access. Items 1 and 2 of the checklist below (server env incl. `WHATSAPP_WEBHOOK_SECRET`, gateway webhook, session connected) are written up in `docs/buddy-handoff.md` to send him. Migration 0040 is applied on Sydney. Waiting on the buddy; then push (deploys), real-phone test, scoped tick, scheduler on.

### Switch-on checklist (do in this order; `chhaon.scheduler.enabled` stays false until step 8)
1. Owner reviews and merges the branch; deploy only when the owner says so (a push touching `api-java/**` deploys to production).
2. Sydney database: apply `20261104000040_scheduler_heartbeat.sql` and (optional) `20261104000041_checkin_message_id.sql` by hand (it has no migration history), plus 0038/0039 if still pending. Check: `select public.scheduler_health();` and `select column_name from information_schema.columns where table_name='checkins' and column_name='whatsapp_message_id';`
3. Server `/etc/chhaon/backend.env`: `WHATSAPP_WEBHOOK_SECRET` (new: `openssl rand -hex 32`), `WHATSAPP_GATEWAY_URL`, `WHATSAPP_GATEWAY_TOKEN`, `WHATSAPP_SENDER_SESSION_NAME` (must match the linked session name); leave `CHHAON_SCHEDULER_ENABLED` unset. **Never enable the scheduler with `WHATSAPP_GATEWAY_URL` blank**: dry-run would mark doses `sent` with fake ids and the family would get false "missed" alerts. Restart the backend.
4. Gateway dashboard, Webhooks: URL `https://api.chhaon.site/webhooks/whatsapp-gateway`, the same secret, events `message.received` and `message.status`; press Test (must show 200). Without the secret the endpoint answers 503 by design.
5. Send one real test: from the app (or `POST /recipients/{id}/consent-request` with a login) for the owner's own parent profile (number = a phone you hold); reply `1` on that phone; check `consent_at` is set and the confirmation arrived. Also test `STOP` then `START` from that phone. The backend log shows `inbound reply handled from ***xyz intent=...`, never the text.
6. Before any tick, silence the stale backlog (13+ doses are `pending` since 2 Nov 21:00 IST, so the first tick would notify every family "reminder not sent"): `update public.dose_events set status='skipped' where status='pending' and scheduled_at < now() - interval '60 minutes';` (count first with a `select count(*)`). Decide separately whether to tell pilot families reminders were paused.
6b. **DONE 2026-11-05 (owner's decision): the 13 stale pending doses were marked `taken`, not `skipped`** (`status='taken'`, `responded_at=now()` on the day, stock counters NOT changed on purpose: `consume_stock` raises refill alerts to families). They are real families' history: the adherence figures and doctor summary count them as taken although nobody confirmed them (reminders had stopped on 2 Nov). To find them: `dose_events` with these ids: 81728453-064e-489b-b2d5-b035e20b89df, 43223824-5780-4e72-9a69-055f6e789589, c89b4105-0556-4264-9f0f-df199ba8e40b, f825b410-6eb4-4856-a6c5-1c08d61b2904, ebf9df8d-5c87-44ce-9476-71050461d171, b3f8bc95-8b94-4c8b-ac7f-8a18fc519f68, c63a86d3-c0fa-452d-967b-c5038be12ee5, fa721e77-6a7c-4d4d-abb4-a8b746838374, c8c0afd1-8fd0-4f76-9227-246710542288, 928460f4-1f0a-4281-ae6a-90eefca00ef3, 02e4b840-bc03-4088-bb72-d6aec6a9ccbe, fc59e212-7b78-41fd-8fca-939b51819a9c, c9c2d6ab-2063-4189-8f4c-337a0eb00a1f. Open: whether to correct them to `skipped` later, and whether to lower stock for the medicines involved.
7. **Scoped switch-on for ONE parent (changed 2026-11-05: the old admin-token tick cannot be used, Supabase logins carry no admin flag).** GitHub secrets (Settings, Secrets and variables, Actions): `CHHAON_SCHEDULER_ENABLED` = `true` and `CHHAON_SCHEDULER_ONLY_RECIPIENT` = the test parent's recipient id (the owner's own number; the id appears in the `consent-request` URL in the browser's Network tab), then run Backend Deploy. Only that parent is touched. An unreadable value runs nothing. Expect the dose reminder on that phone ("Reply *1* once you have taken it"); reply `1`; check the dose is `taken` and stock dropped; the daily check-in the next morning. Check `select public.scheduler_health();` shows a recent tick.
8. **Widen to everyone:** set `CHHAON_SCHEDULER_ONLY_RECIPIENT` to `all` (a blank secret keeps the old value on the server, so use the word `all`) and run Backend Deploy. Watch `scheduler_health()` (seconds_ago stays under about 400) and the first morning's replies. **To switch off:** set `CHHAON_SCHEDULER_ENABLED` to `false` (not deleting the secret, since a blank value keeps what is on the server) and redeploy.

### DECISION 2026-11-05 (owner): existing parents must agree AGAIN on the new WhatsApp number before they get reminders
Parents who said yes under the old Meta number have `consent_at` set in the database, but they agreed to messages from a different number. The owner wants them asked again from the gateway number, so the scheduler is **NOT** widened to everyone yet.
- **Now:** scheduler limited to the owner's test parent Papa (`CHHAON_SCHEDULER_ONLY_RECIPIENT` = `864965ca-3741-45a7-a99b-924d55992d11`, `CHHAON_SCHEDULER_ENABLED` = `true`). Papa agreed through the new number on 2026-11-05 (the typed `1` reply worked end to end).
- **Re-consent plan (not done):** (1) tell the pilot families' caregivers "reminders and messages now come from a new number, save it as Chhaon"; (2) reset the old agreement so the app shows "Waiting for [parent] to agree" with the **Send again** button (it only appears when `consent_at` is empty): `update public.care_recipients set consent_at = null, consent_requested_at = null where consent_at is not null and id <> '864965ca-3741-45a7-a99b-924d55992d11';` (look at the count first with a `select count(*)`; this stops reminders to those parents until they answer); (3) each caregiver presses Send again, the parent replies `1`; the limits are one request per phone number per 10 minutes and 10 per caregiver per day; (4) when enough parents have answered, set `CHHAON_SCHEDULER_ONLY_RECIPIENT` to `all` and run Backend Deploy. Parents who reply `2` or `STOP` stay off. A bulk "ask everyone" button or script is not built.

### DONE 2026-11-05 (owner's decision, test data): everyone marked as agreed by hand, scheduler set to run for everyone
Instead of the re-consent plan above, the owner ran `update care_recipients set consent_at = now() where consent_at is null and opted_out_at is null and whatsapp_phone is not null and whatsapp_phone not in (select phone from phone_opt_outs)`. It changed 8 profiles (Vijay, Sunil Meena, Test, Mamma, Abc, Mishrilal choudhary, Rajan, Shivani; ids in the owner's SQL results), and the 5 older agreements from the Meta days were left as they were. The owner stated these are test profiles with numbers they control. GitHub secrets set: `CHHAON_SCHEDULER_ENABLED` = `true`, `CHHAON_SCHEDULER_ONLY_RECIPIENT` = `all`.
- **Before real families are added:** their agreement must come from the parent's own `1` reply (the Send again button), never set by hand. The 13 stale doses marked taken and these hand-set agreements are test-data shortcuts, not a pattern for real customers.
- To stop reminders: set `CHHAON_SCHEDULER_ENABLED` to `false` and run Backend Deploy.

## Upcoming: tap-to-answer for parents (analysed 2026-11-05, NOT decided, NOT started)

**Why:** with the self-hosted WhatsApp gateway (unofficial, Baileys) parents must TYPE a number (`*1*` / `*2*`); with Meta's official API they tapped a real button. WhatsApp does not reliably show buttons from an unofficial sender, so a tap needs one of the options below. The typed texts were re-laid-out on 2026-11-05 (greeting, news, answers on their own lines with bold numbers) as the interim.

| Option | What the parent sees | Effort | Catch |
|---|---|---|---|
| **A. Meta's official WhatsApp Cloud API** | real tap buttons ("Taken") like before | port the old Python sender + webhook to Java (about 350 lines incl. tests), Meta setup and approved templates (owner) | per-message fee (why the move away happened); needs a number registered with Meta |
| **B. WhatsApp poll through the gateway** | a card with tappable options ("Taken" / "Not yet") | about 350 to 500 lines in total, 1.5 to 2 days incl. a real-phone test (see below) | the gateway never reports votes; vote decryption in Baileys 7.0.0-rc.9 is unproven; the gateway part is deployed by the buddy |
| **C. Keep typed replies (done, polished)** | bold "Reply *1*" lines | none | typing a number is a hurdle for some older parents |

**Recommendation:** C for the pilot; decide A (or B) once reply rates and willingness to pay are known. A also removes the ban risk of an unofficial sender.

**Option B in detail (so it can be picked up later)**
- The gateway already has a send-poll route (`whatsapp-gateway/src/app/api/messages/[sessionId]/[jid]/poll/route.ts`). It does NOT handle votes: `src/modules/whatsapp/store/index.ts` (`messages.update`) only forwards delivered/read status, and `src/lib/webhook.ts` has no poll event.
- Gateway change (TypeScript, about 100 to 150 lines): keep the poll's original message and its secret when it is sent, decrypt each vote (`getAggregateVotesInPollMessage`), dispatch a new webhook event `poll.vote {pollKeyId, voter, selectedOptions}`, add it to the event list in the dashboard. The pipeline deploys only the Java backend, so the buddy deploys the gateway.
- Java change (about 160 lines + 150 to 250 lines of tests): `WhatsAppSender.sendPoll(...)` using the gateway poll route; send dose reminder, check-in and consent as polls; store the poll message id in `dose_events.whatsapp_message_id` (and the check-in equivalent); handle `poll.vote` in `GatewayEventService` (map option to Taken / Not yet / OK / Call me, idempotent, a retracted vote = no answer).
- Risks: vote decryption unproven; a vote can be changed or removed; senders the gateway cannot turn into a phone number (`@lid`) are still ignored; a poll cannot carry the medicine photo; the ban risk of an unofficial sender stays.
- **Cheap feasibility test first (about 1 hour, buddy, no code from us):** send one poll to a phone with the gateway's poll route (`POST /api/messages/{sessionId}/{jid}/poll`, API key header `X-API-Key`), tap an option, and check whether anything about the vote reaches the gateway's webhook log. If a decrypted vote shows up, build B; if not, do not.

## DECISION 2026-11-05 (owner): new WhatsApp number, keep the self-hosted gateway
The old gateway number was banned on 2026-11-05. The owner linked a NEW number to the same gateway and keeps the unofficial route
(Meta's official API stays on hold; cost estimate in this file's history: about ₹100-400/month for 30 families).
- [ ] Parents agree again on the new number (Send again in the app, parent replies 1). Never set consent_at by hand for real families.
- [ ] Tell families to save the new number as "Chhaon" (saved contacts are reported less).
- [ ] Restart reminders for ONE parent first (CHHAON_SCHEDULER_ONLY_RECIPIENT), then everyone.
- [ ] Lower ban risk: only consented parents, low volume at first, no burst sends, no links; keep a spare number ready.
- [ ] Still open: the "0 of 1 taken" reply seen before the ban (SESSION_HANDOFF.md 0.6).

## Note 2026-11-05: a new medicine appears on Today only after the next scheduler run (up to 5 minutes)
Saving a medicine stores its schedule; the day's dose entries (`dose_events`) are made by the scheduler's `generate_dose_events`,
which runs every 5 minutes. So a medicine added now shows on the Today screen within about 5 minutes, not at once.
Also by design: a reminder time that has already passed today gets no dose today (`scheduled_at >= created_at`); the first dose is the
next one in the future (for example an 08:00 medicine added at 14:30 starts tomorrow). Nothing is sent late.
- [ ] Possible improvement (not decided): create today's future doses at once when a medicine is saved, and/or show a short note after saving
      ("It will appear on Today within 5 minutes" / "First reminder: tomorrow 8:00").

## Tablet colour and shape ("neeli gol goli") (built 2026-11-05 on branch `pill-look-v2`, NOT pushed, NOT on Sydney)
Idea from Dr. Deepak Birla (2026-11-05): parents and home helpers who cannot read the strip pick the tablet by colour and shape.
- Medicine form: optional "How does it look?" with 9 colours and 4 shapes (round, oval, long, capsule); shown on the medicine list and details.
- WhatsApp dose reminder gets one extra line, e.g. "🔵 Blue round tablet" / "🔵 नीली गोल गोली" (Hindi colour agrees with the noun).
- Database: migration `20261105000042_pill_look.sql` (two columns `pill_colour`, `pill_shape` with fixed lists).
- Saved and loaded through the Java API (`/api/v1/recipients/{id}/medicines`, `/api/v1/medicines/{id}`), fields `pillColour` / `pillShape`.
  The backend checks the same fixed lists (anything else = 400). On create: null or "" = not set. On update (PUT): field left out or
  null = KEEP the saved value, "" = clear, a listed value = set. The web form always sends both (its "no choice" is ""), and an older
  cached app that does not know the fields never wipes them.
- **Deploy order matters (the backend checks its tables at start-up, `ddl-auto=validate`; the new entity fields need the columns):**
  1. [ ] Paste `supabase/migrations/20261105000042_pill_look.sql` into the Sydney SQL Editor (safe to run twice; the current backend and
     web keep working with the extra columns, nothing reads them yet except the scheduler, which reads them through `to_jsonb`).
  2. [ ] Then deploy the backend (Backend Deploy). If it is deployed BEFORE step 1 it fails to start (missing column `pill_colour`).
  3. [ ] Then the web app. The web is safe with an old backend too (missing fields are read as "not set"), but saving a colour has no
     effect until step 2 is live, so deploy it last.
- [ ] Hindi words reviewed by a native speaker (`docs/hindi-review.md`, Part 4).
- [ ] Later, if asked: show the colour on the Today screen and the emergency card; a "two colours" option for capsules.
- Note: 2 Java tests (`OtpServiceTest`) fail on `main` too, before this change; not looked into yet.

## 2026-11-05: old storage files (Mumbai) are LOST
The buddy confirmed the medicine photos and report files of the old Mumbai project were not copied before the move to Sydney.
Only the owner's own family/test data is affected. Clean-up: count the rows whose file is missing (medicines.photo_url not in
storage.objects bucket `medicine-photos`; document_files.path not in bucket `reports`), then clear those photo links and delete report
entries whose files are all gone.
- [x] DONE 2026-11-05 on Sydney: 6 medicine photo links cleared (Fur-XT, MGD3, Rekfa, Ecosprin 150, Argipreg, Carich) and 2 reports
      deleted ("Logo", "Office"); all of them pointed to lost files. Migration 0042 (pill colour/shape columns) applied on Sydney too. `supabase/maintenance/copy_storage.mjs` is no longer needed for this.


## Privacy: "Download my data" / "Delete my account" / "Delete this parent" ported to Java (2026-11-05, branch `privacy-port`, NOT pushed)
`POST /privacy/delete-account`, `POST /privacy/recipients/{id}/delete` and `GET /privacy/export` now exist in `api-java/.../privacy/`
(same paths, bodies and answers as the Python `api/app/routes/privacy.py`). Database rows are erased exactly as before
(`erase_caregiver` + deleting the profile, which also removes the password credential, notifications and pending login codes).
- [ ] **Stored files are NOT erased yet.** The Java backend has no Supabase Storage access (no Supabase URL / service-role key
      in its settings), so when a parent or an account is deleted these files stay in the buckets, unreachable but not gone:
      `medicine-photos/<recipient_id>/...`, `reports/<recipient_id>/...` (wallet documents and report photos/PDFs), and
      `feedback/<user_id>/...` (feedback screenshots; their links in `feedback.images` ARE cleared). Python deleted them first.
      To finish this, the backend needs two settings (new GitHub secrets wired into the deploy like the others): `SUPABASE_URL`
      (`https://utjgegiioflwnmesmgcv.supabase.co`) and `SUPABASE_SERVICE_ROLE_KEY` (Sydney dashboard, Project Settings, API; a
      secret: never paste in chat or commit). Then port `_purge_folder` / `purge_recipient_files` from `api/app/services/privacy.py`
      (Storage REST: `POST /storage/v1/object/list/<bucket>` with a prefix, then `DELETE /storage/v1/object/<bucket>` with the
      paths), call it before the database delete, and also use it for the two `TODO(port storage)` spots in
      `CareRecipientService.delete` and `MedicineService` (medicine photo). Files from before 2026-11-04 are already lost (see above).
      Until then: on a deletion request, the owner can remove the folders by hand in the Sydney Storage dashboard.
- [ ] The old Supabase Auth user (email login) is removed too, best effort (`delete from auth.users`); if the database user of the
      backend may not do that, the erasure still succeeds and a warning is logged (no phone or health data in it).

## Care type + Pregnancy (docs/use-cases.md step 1, built 2026-11-05 on branch `care-type`, NOT pushed, NOT on Sydney)
- "Who are you caring for?" on the add/edit person form: Older parent (default; every existing profile) / Pregnancy / Child (shown as
  "coming soon", not selectable). Pregnancy adds an optional due date and words the form, the WhatsApp preview and the "Add another
  person" button for her.
- Today: pregnancy card "Week 24 · 2nd trimester · 112 days to go (due 25 Feb 2027)", counted on HER time zone. Nothing about the baby.
- Add medicine (pregnancy only): name chips "Iron + folic acid", "Calcium + vitamin D3", "Folic acid" that fill the NAME only, with
  "Use the dose your doctor prescribed." (no dose placeholder on her form).
- Doctor visits (pregnancy only): "Common checks — confirm with your doctor" with week windows; "+ Add" opens a NEW appointment with the
  check's name in the notes and a suggested date in the window. List in `web/src/lib/pregnancyChecks.ts`.
- WhatsApp: pregnancy dose reminder and daily check-in with a time-of-day greeting and "Week 24 today 💛"; same *1* / *2* replies.
  Older parents get exactly the old texts.
- Database: migration `20261105000043_care_type.sql` (`care_type` older|pregnant|child default older, `due_date` pregnant only).
- API (`/api/v1/recipients`): `careType` ("older" | "pregnant"; "child" refused until step 3) and `dueDate` ("YYYY-MM-DD"). Left out =
  keep (new profile: older), dueDate "" = clear. Due date window: at most 300 days ahead, 60 days past, checked only when it changes.
  Switching away from pregnant clears the due date. The caregiver's own profile ("Me") stays older.
- **Deploy order (the backend checks its tables at start-up, `ddl-auto=validate`):**
  1. [ ] Paste `supabase/migrations/20261105000043_care_type.sql` into the Sydney SQL Editor (safe to run twice; the current backend
     and web keep working with the extra columns).
  2. [ ] Then deploy the backend. Deployed BEFORE step 1 it fails to start (missing column `care_type`).
  3. [ ] Then the web app (it reads missing fields as "older", so it is safe with an old backend, but the choice is not saved until step 2).
- [ ] Gynaecologist reads the usual checks and supplement names (`web/src/lib/pregnancyChecks.ts`) before families are told.
- [ ] Hindi reviewed by a native speaker (`docs/hindi-review.md`, Part 7).
- [ ] Decide later: a per-person on/off for the daily check-in (today a pregnancy gets the check-in with the gentle wording).

## Medicine photo in the WhatsApp reminder (feature 5, built 2026-11-05 on branch `f5-photo-reminder`, NOT pushed)
When a medicine has a photo, the dose reminder is sent as an IMAGE with the usual reminder text as its caption (helper and pregnancy
wording unchanged), so a parent or helper who cannot read the strip sees it. The gateway's message id is stored in
`dose_events.whatsapp_message_id` as before, so swipe-replies still match. ANY photo problem (no photo, storage not configured, file
missing, download error, not a readable image, cannot get under 1 MB, gateway refuses media) sends the plain text reminder in the
same tick; nothing else changes. The photo is a JPEG of at most 1 MB (app photos are already 1280 px JPEG and go as they are; anything
else is shrunk). Only files in the person's own folder `medicine-photos/<recipient_id>/...` are read. No migration.
- [ ] **Owner/buddy: add two GitHub secrets** (repo Settings -> Secrets and variables -> Actions), then redeploy the backend:
      `SUPABASE_URL` (the Sydney project address, `https://<project-ref>.supabase.co`, must be https) and
      `SUPABASE_SERVICE_ROLE_KEY` (Sydney dashboard -> Project Settings -> API keys -> service_role, or a secret `sb_secret_...` key).
      A secret: never paste it in chat, never commit it, never put it in the web app. These are the SAME two settings the
      privacy note above needs for erasing stored files.
      Until both are set, photos are off and every reminder is plain text (the start-up log says
      "medicine photos in reminders: off"). Note: a blank secret in the deploy KEEPS the value already on the box; to switch photos
      off again, remove the two lines from `/etc/chhaon/backend.env` on the box and restart.
- [ ] After switching on: add a photo to one test medicine and check on a test phone that the reminder arrives as a picture with
      the text below it, and that a swipe-reply "1" to the picture marks the dose taken.
- [ ] Possible duplicate: if the gateway sends the picture but its answer is lost (connection dropped after sending), the plain
      text follows too. Rare; acceptable (better twice than never).
