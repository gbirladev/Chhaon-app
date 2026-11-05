# Session handoff: everything needed to continue on a different Claude account

Written 2026-11-04, updated 2026-11-05 (see section 0, which is the newest and overrides sections 4, 5 and 7 where they differ), for Ganesh and for the next Claude. **Read this file first, then `CLAUDE.md`, then
`docs/TODO.md`.** The previous Claude's private memory is not available to a different account, so the important parts of it are
copied here (section 2). Nothing in this file is a secret: no keys, no passwords, no tokens. Never put any into the repo or a chat.

Suggested first message to the new Claude: "Read docs/SESSION_HANDOFF.md, CLAUDE.md and docs/TODO.md, then tell me where we are and
what you suggest next. Do not push anything."

---

## 0. UPDATE 2026-11-05: read this first (newest state, overrides older sections)

### 0.1 The situation right now
- **The WhatsApp number linked to the self-hosted gateway was BANNED by WhatsApp** (reported by the owner on 2026-11-05, shortly after reminders were switched on for everyone). The gateway is the unofficial route (Baileys, `whatsapp-gateway/`, https://wa.chhaon.site). This is the risk that was accepted to save cost. Details of the ban (exact wording, whether "Request a review" is offered) were not yet collected.
- **The scheduler is probably still ON in production** (GitHub secrets `CHHAON_SCHEDULER_ENABLED` = `true`, `CHHAON_SCHEDULER_ONLY_RECIPIENT` = `all`). **First action for the next session: confirm with the owner that `CHHAON_SCHEDULER_ENABLED` is set to `false` in GitHub Actions secrets and that Backend Deploy has been run afterwards** (a blank value keeps the old value on the server, so it must be the word `false`). Until then the backend keeps trying to send from a banned number and may mark doses "sent" that were never delivered.
- Before the ban the full chain worked: the consent request reached the owner's phone, typed `1` was received through the webhook, the parent was recorded as agreed and the "Thank you" reply came back; at least 3 dose reminders were sent (`dose_events.status = 'sent'`). One open observation: the owner replied `1` to a dose reminder and the app still showed "0 of 1 taken"; not investigated (likely several test profiles share one phone number, so the reply went to another profile's dose; the diagnostic query is in 0.6).

### 0.2 Decision needed: how to send WhatsApp messages (the unofficial route is not safe for real families)
| Option | State |
|---|---|
| **A. Meta's official WhatsApp Cloud API** (real tap buttons, no ban risk, approved templates, per-message fee) | Recommended by the previous Claude for anything beyond the pilot. The deploy workflow still passes `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET` (may still exist as GitHub secrets from the Render days). The Java backend has NO Meta sender or Meta webhook yet. Spec to port: `api/app/whatsapp/*.py`, `api/app/services/messaging.py`, `docs/whatsapp-setup.md`, `docs/whatsapp-templates.md`, `docs/whatsapp-templates.json`. Estimated about 350 lines plus tests; the owner does the Meta setup. Known earlier Meta issues: delivery errors 132018 (Hindi photo template had no image header) and 131049 (Meta declined to deliver some Marketing-category messages), see `docs/TODO.md`. |
| B. WhatsApp poll via the gateway | Analysed, NOT built, not recommended now: the gateway never forwards votes (see `docs/TODO.md`, "tap-to-answer"). |
| C. Typed replies through a gateway number | Built and working (texts re-laid-out 2026-11-05). Only acceptable if a number survives; ban risk remains. |
| Appeal | If WhatsApp offers "Request a review", press it on the phone; do not relink the same number to the gateway before it is cleared. |
The owner had said "keeping typed reply for the pilot" before the ban; that choice must be revisited.

### 0.3 Everything done on 2026-11-04/05 (all pushed to origin/main unless marked)
- **SEO (live):** keyword titles/descriptions, canonical links, social preview image `web/public/og-image.jpg`, structured data, `robots.txt`, `sitemap.xml` (5 pages), Hindi home page `/hi/` (GENERATED from `web/public/home.html` by `web/scripts/build-hindi-home.mjs`; legal pages by `build-legal-pages.mjs`), favicon `.ico` + 96 px PNG + SVG, old "Vatsalaya" pages `/privacy.html` and `/about.html` now redirect to `/legal/privacy.html` and `/`, Play Store doc points to `/legal/privacy.html`. Google Search Console (Domain, verified by DNS TXT at Hostinger, owner `gbirla.dev.rnd@gmail.com`) with the sitemap submitted and indexing requested, Bing imported. Google's result still shows an old snapshot (login-screen text) until it re-crawls; the favicon in results takes 1 to 3 weeks.
- **Backend deploy pipeline:** Backend Deploy is green again. The red mark earlier was only the last step (smoke test) because the GitHub secret `HEALTH_CHECK_URL` was wrong; it must be exactly `https://api.chhaon.site/actuator/health`. The server's env file is written from GitHub secrets at each deploy (empty secrets keep the value on the box). New secrets wired through `.github/workflows/backend-deploy.yml` and `api-java/deploy/aws/send-ssm-deploy.sh`: `WHATSAPP_WEBHOOK_SECRET`, `CHHAON_SCHEDULER_ENABLED`, `CHHAON_SCHEDULER_ONLY_RECIPIENT`. Other secrets used by the gateway: `WHATSAPP_GATEWAY_URL` (now `https://wa.chhaon.site`), `WHATSAPP_GATEWAY_TOKEN` (a `wag_` API key), `WHATSAPP_SENDER_SESSION_NAME`.
- **Three real bugs fixed in the Java backend** that made every web call fail: (1) no CORS (the browser's pre-flight got 403), now `https://chhaon.site` is allowed (`app.cors.allowed-origins`, env `CORS_ALLOWED_ORIGINS`); (2) the Sydney Supabase project signs login tokens with **ES256**, the backend only knew HS256, so every real token got 401; now `SupabaseJwtVerifier` verifies against the project's public JWKS (`app.supabase.jwks-url`), only `role=authenticated` tokens count; (3) consent send failures now return a safe reason in the 502 (`[gateway_unreachable]`, `[gateway_key_rejected_401]`, `[session_name_not_found]`, `[gateway_refused: ...]`). 432 Java tests pass.
- **WhatsApp texts we start** were re-laid-out (greeting, news, answers on their own lines with bold numbers) and the Hindi consent grammar was fixed (`MessageTexts.java`); `docs/hindi-review.md` notes it; parent-reply texts (`ReplyTexts.java`) are unchanged.
- **Scheduler can run for ONE recipient first** (`chhaon.scheduler.only-recipient`; an unreadable value runs nothing; `all` or empty = everyone). Note: `chhaon.scheduler.enabled` must never be written in `application.properties` (a test checks that); it comes from the env var.
- **Production data changes done by hand (test data, owner's decisions):** 13 stale `pending` doses marked `taken` (ids in `docs/TODO.md`, stock untouched); 8 profiles marked as agreed (`consent_at = now()`: Vijay, Sunil Meena, Test, Mamma, Abc, Mishrilal choudhary, Rajan, Shivani) plus 5 older agreements from the Meta days left as they were. The owner stated these are test profiles with numbers they control. **Before real families join, agreement must come from the parent's own `1` reply; never set it by hand.**
- **Webhook and gateway state:** the gateway webhook `Chhaon backend` was created in the gateway dashboard (URL `https://api.chhaon.site/webhooks/whatsapp-gateway`, events `message.received`, `message.status`) after the owner's account was given access by the buddy (the gateway lets only SUPERADMIN, the session owner, or a shared-access user manage a session). The webhook secret was regenerated and set in GitHub and in the gateway; an HMAC-signed test request got `ignored:event` HTTP 200.
- **Consent resend rule explained:** one request per phone number per 10 minutes across all families/accounts (`claim_consent_request`); a 10 minute countdown in the app is normal.

### 0.4 Local commits not yet pushed (docs only): `0c4ef43`, `eb7017d`, plus the commit of this file. A push of docs-only changes triggers no deploy of the backend (paths filter) but the web workflow only runs on `web/**`.

### 0.5 What to do next (suggested order)
1. Make sure the scheduler is OFF (0.1). Tell the pilot families reminders are paused (still true).
2. Collect the exact ban wording from the owner; if "Request a review" exists, use it.
3. Decide the sending route (0.2). If A: ask the owner for the Meta status (is the WhatsApp Business account and the approved template list intact? are the four `WHATSAPP_*` secrets still valid?), then port the sender + webhook to Java on a branch with tests (never push without being asked).
4. Investigate "0 of 1 taken" (0.6).
5. Re-run the switch-on checklist in `docs/TODO.md` only on a route that cannot be banned.

### 0.6 Useful read-only queries (Sydney SQL Editor; it shows only the LAST result of several statements)
- `select public.scheduler_health();` (null = no tick recorded yet; otherwise `seconds_ago` under about 400 means the scheduler is alive)
- `select status, count(*) from public.dose_events where scheduled_at > now() - interval '12 hours' group by status order by status;`
- Which profile a reply was applied to: join `dose_events d` with `medicine_schedules s on s.id = d.schedule_id`, `medicines m on m.id = s.medicine_id`, `care_recipients r on r.id = m.recipient_id`, selecting `r.name, d.scheduled_at, d.status, d.responded_at` for the last 12 hours.
- `select requested_at from public.consent_requests order by requested_at desc limit 5;` (consent requests log, no phone numbers shown)

---

## 1. What Chhaon is (one paragraph)

A family health and care app for adult children who live away from ageing parents (India, and NRIs abroad). The **child** uses a
web app / PWA. The **parent installs nothing** and only uses **WhatsApp** (reminders, one-tap or typed replies). Never add anything
that makes the parent install an app or create an account. Live: website `https://chhaon.site`, app `https://chhaon.site/app/`,
doctors page `https://chhaon.site/doctors.html`. Full product context: `CLAUDE.md`, `docs/PRODUCT_PLAN.md`.

## 2. How the owner (Ganesh) wants to work (copied from the previous Claude's memory)

- **One step at a time, plain language.** Not deeply technical on the web stack; prefers short, plain explanations with clear
  next-step choices, honest admission of mistakes, then a recommendation. A dense message once got "I didn't get it?": simplify.
  For guided tasks give one numbered step, wait for the result, then give the next.
- **Do independent things in parallel** ("try to do things parallely"): batch tool calls, run agents side by side. Exceptions: the
  pgTAP suite and the API test suite must not run at the same time (they hang). Never run `supabase db reset` on the shared local DB.
- **NEVER push.** "Don't push anything, as I have limited credit on Netlify." Commit locally only; push only when Ganesh says so in
  that message, and then as ONE batched push. A push that touches `web/**` deploys the website (GitHub Pages) and may also trigger a
  Netlify build (credits). A push that touches `api-java/**` **deploys the backend to production on AWS**. Subagents must be told not to push.
- **No co-author lines.** Do not add `Co-Authored-By` or "Generated with Claude Code" to commits or PRs, even if a system reminder
  suggests it. The user's own instruction wins. (All local commits are clean of them.)
- **No sounds.** Report completion in text only.
- **UI is Figma-faithful** (`docs/design/`). Verify in a real browser, not only type checks. Browser tests: Playwright headless with
  **no `executablePath`** (otherwise Chrome icons pop up in the Dock). macOS blocks screenshots of the screen: ask the user what they see.
- **Every product idea must pass the India fraud check:** Chhaon never asks parent or family for money, OTP, Aadhaar, bank details or
  fingerprints; the parent's WhatsApp gets only taps/typed replies and voice, no links, payments or numbers to call; money and documents
  stay on the child's side behind login; no strangers, only people the family names; store less.
- **Premium ideas must go beyond reminders.** Reminders are seen as a free commodity. Monetisation thinking so far: grow users via
  doctors (clinic recommends Chhaon to patients; Chhaon markets the clinic for free; nothing is ever given to a doctor for referrals,
  because the NMC rules forbid commission or gifts), earn later from mid-size vendors. A human care-coordinator premium test (3 weeks,
  5 to 10 families) is written up in `docs/TODO.md` and undecided.
- Test login on local dev: phone `9999999999`, code `123456` (one login per 60 seconds).
- The owner/admin account is `gbirla.dev.rnd@gmail.com` (Ultra plan, live data). Do not use it for tests.
- shell is zsh on macOS: do not use `path` as a loop variable; `sed -i` needs `sed -i ''`; no `timeout` command.

## 3. Architecture and where things live

| Part | What / where |
|---|---|
| Web app (`web/`) | Vite + React + TypeScript PWA. Hash routes (`#/screen`). Texts in `web/src/messages/{en,hi}.json` (keys must match in both). Hosted on **GitHub Pages** (`.github/workflows/web-deploy.yml`, CNAME chhaon.site). Home page `web/public/home.html` becomes `/`, the app is at `/app/` (plugin `siteLayout()` in `vite.config.ts`). |
| Backend | **Java is the one backend** (decision "Option B", 2026-11-04): `api-java/` (Spring Boot 3.4, Java 21, Maven, JdbcTemplate, Flyway). Runs on AWS EC2 Sydney, deployed by GitHub Actions on push to `api-java/**`. The old Python API `api/` (Render, Meta Cloud API) is **no longer deployed** and is only the reference spec to port from. |
| Database / Auth / Storage | Supabase, **Sydney project ref `utjgegiioflwnmesmgcv`** (moved from Mumbai by the buddy; the old Mumbai project appears to be deleted). Migrations are in `supabase/migrations`, pgTAP tests in `supabase/tests`. |
| WhatsApp | Self-hosted gateway `whatsapp-gateway/` (Baileys, WA-AKG). **No real quick-reply buttons**: messages are plain text that ends with how to reply (1 = yes/taken/I am okay, 2 = no/call me, STOP, START). Webhooks are signed `X-Webhook-Signature: sha256=<hmac>`; events `message.received`, `message.status`. Unofficial gateway means a WhatsApp ban risk, accepted by the owner for cost reasons. |
| Email login | Supabase Auth with custom SMTP using the Gmail account `chhaon.app.mail@gmail.com` (works; OTP by email confirmed). |
| Local dev | Local Supabase 127.0.0.1:54321/54322 (container `supabase_db_carelink`), local Python API on 8000 (practice mode), preview servers on spare ports. No JDK on the Mac: Java tests run through Docker (see section 9). |

**Never apply SQL to the Sydney database with `supabase db push` or `config push`.** It has no migration-history table. Apply SQL by
pasting into the Sydney Supabase **SQL Editor**. Table/function permissions (GRANTs) were lost in the copy from Mumbai and were
repaired with `supabase/maintenance/restore_*.sql`; checks: `table_permissions_check.sql` (26 expected lines), function check `4|40|51|51`.

## 4. State of production right now (2026-11-04)

**Live and working:** website routing, doctors page and QR cards, email OTP login, emergency card (`/app/#/e/<token>`, migration 0038
applied), pilot plans (everyone on Family, admins on Ultra, "Your plan" screen; `PLANS_ENABLED=false` so nothing is charged; migration
0039 applied), repaired permissions, **storage buckets and rules (restored today; report and medicine photo uploads confirmed working by
the owner)**, migration **0040 scheduler heartbeat applied today** (`select public.scheduler_health()` returns `null`, which is correct
until a tick records one).

**BROKEN: reminders have not been sent since 2026-11-02 21:00 IST.** Cause: after the move to AWS the Java scheduler is off
(`chhaon.scheduler.enabled` / `CHHAON_SCHEDULER_ENABLED` defaults to false and the deploy never sets it) and Java had no reply webhook.
About 13 doses are stuck `pending`. Pilot families should be told reminders are paused (owner action).

**Not live (local only, see section 5):** everything built in the last sessions after the last push, including the app's "Reminders are
delayed" and "Reminders have stopped" cards, time-zone box, installed-icon redirect, dark/light mode, the Java reply pipeline.

## 5. Local commits waiting for ONE push (main is 23 commits ahead of origin/main; NOTHING pushed)

Main includes (newest first, abbreviated): Hindi review page; buddy handoff (3 commits); merge of dark/light mode; merge of
`java-reply-pipeline`; TODO updates; storage maintenance + copy tool; heartbeat (web part + Java part); "Reminders are delayed" card;
time-zone box + installed-icon fix; permission-repair scripts. Use `git log --oneline origin/main..main` for the exact list.

**Pushing deploys things:** web files deploy the site; `api-java/**` deploys the backend (build, S3, SSM, health check, rollback).
The new webhook endpoint fails closed (answers 503 until `WHATSAPP_WEBHOOK_SECRET` is set), so deploying early is safe. The scheduler stays
off by default. Before pushing, suggest the owner stops/unlinks Netlify builds (Netlify dashboard, Project configuration, Build and deploy).

**Other branches / worktrees (all under `.claude/worktrees/`):**
- `java-reply-pipeline` and `worktree-agent-a434f2a5bab65d04b` (dark mode): already merged into main.
- `worktree-agent-a29a22a94bf8507be`: **unfinished, untested WIP** (commit e7c4352) of the caregiver-alerts / escalation / appointment-reminders port (new files `AlertDelivery.java`, `AppointmentReminders.java`, `Escalation.java`, `TimeText.java`; changes to `SchedulerService`, consent, reply router/handlers, `MessageTexts`). The agent was stopped mid-work. Treat it as a starting point: review, finish, test; do not merge as is.
- `worktree-agent-a63ec39a84212ed0c`, `-a7d7c6494d0b39062`, `-ad7990e4fa641bf15`, `worktree-agent-ae2d6e24a77efb279`: older agent branches from earlier work; not reviewed in this session (probably already merged or obsolete). Check with `git branch --merged main` before deleting.

## 6. The Java reply pipeline (merged into main, 405 tests)

What exists: `POST /webhooks/whatsapp-gateway` (HMAC check, fails closed: no secret = 503, bad/missing signature = 401, nothing logged);
classifier + router + handlers for Taken, check-in (1 = OK, 2 = call me alerts the family once), consent yes/no, STOP/START; duplicate
protection (each message id claimed inside the same transaction as the work; failure rolls back and returns 500); consent route
`POST /recipients/{id}/consent-request` and public `GET /features`; the scheduler records a heartbeat every tick (best-effort);
plain-text reminder/check-in/consent wording; a bug fix (check-in step now honours STOP numbers). Details: `docs/java-whatsapp-replies.md`,
tests: `docs/java-tests.md`.

Known gaps: a sender the gateway cannot turn into a phone number (`@lid` without a number) is ignored, even if it says STOP; the gateway
never retries, so a 500 loses that reply; delivery failures are not visible (status maps errors to PENDING); real-phone behaviour is
untested. **Update (branch `family-alerts`, local only):** `/alerts/whatsapp/request` and WhatsApp alerts to caregivers are ported
(see "WhatsApp alerts to the family" in `docs/java-whatsapp-replies.md`).
**Not ported yet (needed for parity with the old Python flow):** escalation
ladder, love loop, appointment reminders, delivery-failure detection, medicine photos in reminders, one grouped message per time slot.
The Python spec: `api/app/services/{scheduler,inbound,appointments,messaging}.py`, `api/app/whatsapp/templates.py`, `docs/escalation.md`,
`docs/scheduler.md`, `docs/slot-reminders.md`, `docs/love-loop.md`.

## 7. What must happen to get reminders back (in this order)

1. [x] Apply migration `20261104000040_scheduler_heartbeat.sql` on Sydney. **Done.** (`...0041_checkin_message_id.sql` is optional.)
2. [ ] **Buddy (owner has no AWS or gateway access): `docs/buddy-handoff.md`.** Set `WHATSAPP_WEBHOOK_SECRET` (new, `openssl rand -hex 32`,
   same value in the gateway dashboard), confirm `WHATSAPP_GATEWAY_URL`, `WHATSAPP_GATEWAY_TOKEN`, `WHATSAPP_SENDER_SESSION_NAME`, keep the
   scheduler off, set the gateway webhook to `https://api.chhaon.site/webhooks/whatsapp-gateway` with events `message.received` and
   `message.status`, confirm the WhatsApp session is connected. The doc has step-by-step instructions and two `curl` checks
   (no signature = 401, signed test event = 200 `ignored:event`).
3. [ ] Owner decides to push (one batched push). Run the two `curl` checks.
4. [ ] Real-phone test through the gateway to ONE phone (swipe-reply and typed replies).
5. [ ] Before any tick: silence the stale backlog (mark the ~13 old `pending` doses skipped), otherwise the first tick sends a burst of
   "reminder not sent" alerts to families.
6. [ ] Scoped single-parent tick: `POST /api/v1/admin/tick?recipientId=<test parent id>` with an admin token.
7. [ ] Only then set `CHHAON_SCHEDULER_ENABLED=true` and restart; watch `select public.scheduler_health();`.
   Full 8-step checklist: `docs/TODO.md`, "Switch-on checklist". **Never enable the scheduler with `WHATSAPP_GATEWAY_URL` blank**:
   dry-run marks doses "sent" with fake ids.
8. [ ] Port the remaining alerts/appointments (section 6) soon after.

## 8. Open items and decisions

**Waiting on the buddy:** (a) the handoff in step 7.2; (b) did he copy the old Mumbai storage files before the move? If not, old
medicine photos and report files are gone; the database rows still point to them. Options: families re-upload; optional read-only query
listing rows whose file is missing; check the app shows a placeholder, not a broken image. If he has a copy, `supabase/maintenance/copy_storage.mjs`
loads it (look-only by default; `--apply` copies; never overwrites; keys only as environment variables in the owner's own terminal).
(c) Tell him to keep permissions (grants) if he ever copies the database again; he should use his own account, not `gbirla.dev.rnd@gmail.com`, for setup and tests.

**Owner actions:** send the buddy the handoff and the secret privately; stop/unlink Netlify builds; tell pilot families reminders are
paused; get `docs/hindi-review.md` reviewed by a native Hindi speaker; decide Mumbai vs Sydney for DPDP (India data residency) at some point.

**Small items parked:** "Your doctors" list and a Google Maps "nearest doctor" link (decided: later; a doctor login was decided against
for now); anon table-privilege lockdown (optional, test locally first); "new version available" banner; Supabase Auth Site URL / redirect
should be `https://chhaon.site/app`; "About us" on the doctors page; reports with AI reading are built but were switched off for v1
(`VITE_REPORTS_ENABLED`); the owner did test a report upload in production, so check what `web-deploy.yml` currently sets before
assuming. Safety net and Love messages were enabled in production at the owner's request; admin-only features stay off.

## 8b. SEO and search (done 2026-11-04, live)

- Pushed and live: keyword titles/descriptions, canonical links, social preview image (`web/public/og-image.jpg`, 1200x630), structured
  data (organisation, website, web app, FAQ), `robots.txt`, `sitemap.xml` (5 pages), `/app/` and the two old "Vatsalaya" pages
  (`/about.html`, `/privacy.html`) set to noindex.
- Hindi home page `https://chhaon.site/hi/` with hreflang links; it is GENERATED from `web/public/home.html` by
  `web/scripts/build-hindi-home.mjs` on every build. Edit `home.html`, never `hi/index.html`. The legal pages are generated the same way by
  `build-legal-pages.mjs`. The home page's own script sets `document.title`, keep it in line with the `<title>` tag.
- Google Search Console: Domain property `chhaon.site` verified by DNS TXT at Hostinger (registrar and DNS), owned by
  `gbirla.dev.rnd@gmail.com`; sitemap submitted (Success, 5 pages); indexing requested for `/`, `/hi/`, `/doctors.html`.
  Hostinger DNS keeps the `v=spf1` record and the `google-site-verification=AosOqw0...` record (keep both). The older record and the
  other Google account were removed (done 2026-11-04). Bing Webmaster Tools: imported from Google Search Console (done 2026-11-04).
- Not done yet: guide pages for search ("looking after parents from abroad", "medicine
  reminders for elderly parents on WhatsApp", "what to keep on an emergency card"), links from other sites (clinics, startup listings,
  NRI groups), Hindi review of the search title/description (`docs/hindi-review.md` Part 3), Play Store privacy link should be
  `https://chhaon.site/legal/privacy.html` (not the old `/privacy.html`).

## 9. How to run and test

- **Web (from `web/`):** `npx tsc -b` (types), `npx oxlint src/App.tsx` (baseline **14 warnings, must not increase**), `npm run build`.
  English and Hindi message files must have identical key sets (edit by text insertion; do not reformat the JSON).
- **Database tests:** `npx supabase test db` or via `docker exec -i supabase_db_carelink psql ...`, rolled back (368 checks at last full run).
  Do not run them at the same time as `uv run pytest` (Python API tests, 226).
- **Java tests (405):** `api-java/scripts/make-test-db.sh` (throwaway database `chhaon_java_test` copied from the local schema), then
  `api-java/scripts/mvn-docker.sh -B test` (Maven in Docker). If the test database is unreachable, DB tests skip and the build is green.
- **Browser tests:** Playwright-core headless shell, no `executablePath`, test at 390 px, `page.clock.install` for fake time. The
  scratchpad scripts used in this session (`/private/tmp/...`) are temporary and are NOT in the repo; write new ones as needed.
- Scoped ticks only on the shared local DB: an unscoped tick at a fake time creates check-ins and alerts for every family.

## 10. Notes on past mistakes (to avoid repeating)

- A theory that "reminders stopped because of Sydney time" was wrong; the real cause was the scheduler being off. Check data before theorising.
- `sed -i` without `''` on macOS silently failed and a merge was committed with conflict markers (fixed); always check `grep '<<<<<<<'` after merges.
- Do not trust an agent's "tests pass": re-run them. Review agent branches before merging; agents can hit usage limits or network errors mid-work
  (their work stays saved in their worktree and they can be resumed).
