# Handoff for the backend owner (AWS and WhatsApp gateway)

Written 2026-11-04. Chhaon reminders have not been sent since 2026-11-02 21:00 IST, because the Java scheduler is off
(`CHHAON_SCHEDULER_ENABLED` defaults to false) and the Java backend had no way to receive parents' replies.
The reply pipeline is now written and tested (405 tests) but is NOT deployed. These are the things only you can do.
Nothing here needs the scheduler to be switched on yet.

## 1. Backend settings (the env file on the server)

Add or confirm:

| Variable | What |
|---|---|
| `WHATSAPP_WEBHOOK_SECRET` | New. A random secret, 64 hex characters (`openssl rand -hex 32`). Must be the SAME value as in the gateway dashboard (item 2). Blank means the webhook refuses everything (503). |
| `WHATSAPP_GATEWAY_URL` | The gateway's address. If blank, the sender is in dry-run and marks doses "sent" with fake ids. |
| `WHATSAPP_GATEWAY_TOKEN` | The gateway's API token. |
| `WHATSAPP_SENDER_SESSION_NAME` | The gateway session that sends (default `Chhaon`). |
| `CHHAON_SCHEDULER_ENABLED` | Leave **false** for now. It is switched on last (see `docs/TODO.md`, "Switch-on checklist"). |

The secret should be given to you privately, never in a repository, a chat group or a commit.

## 2. Gateway dashboard: the webhook

- URL: `https://api.chhaon.site/webhooks/whatsapp-gateway`
- Secret: the same value as `WHATSAPP_WEBHOOK_SECRET`
- Events: `message.received` and `message.status`

Please also confirm the WhatsApp session is **connected** (scanned and online) and tell the product owner.

## 3. Deploying

The new backend code (reply webhook, consent route, scheduler heartbeat, plain-text reminder wording) is merged on the local
`main` branch of the product owner and is deployed by the existing GitHub Actions pipeline when it is pushed. The product owner
will push when ready, as one batched push. The webhook endpoint fails closed, so deploying it before the secret is set is safe
(it answers 503 to everything).

## 4. Database (Supabase, Sydney)

- Migration `20261104000040_scheduler_heartbeat.sql` is applied (2026-11-04).
- Migration `20261104000041_checkin_message_id.sql` is optional (lets a swipe-reply match a check-in). The code works without it.
- The Sydney database has no migration history table: apply SQL in the SQL editor, never `supabase db push`.
- **When the database was copied from Mumbai to Sydney, table and function permissions were lost** (grants for the anon,
  authenticated and service roles). The product owner repaired them with `supabase/maintenance/restore_*.sql`. If the database is
  ever copied again, please keep the permissions (for example `pg_dump` without `--no-acl`), and run
  `supabase/maintenance/table_permissions_check.sql` afterwards (it should list 26 expected lines).

## 5. Storage files

The Sydney project had no storage buckets, so report uploads failed and old medicine photos were missing. The buckets and their
rules are recreated (2026-11-04) and new uploads work. **Question: before the move, did you copy the old project's storage files
(medicine photos, reports)?** The old Mumbai project appears to have been deleted. If you have a copy, tell the product owner;
`supabase/maintenance/copy_storage.mjs` can load it.

## 6. After you have done 1 and 2

Tell the product owner. They will push, and then the two of you do the real-phone test and the scoped single-parent tick from
`docs/TODO.md` ("Switch-on checklist"), and only then switch the scheduler on.

Known gaps on the Java side: a sender the gateway cannot turn into a phone number (an `@lid` without a number) is ignored, even
if it says STOP; the gateway never retries, so a 500 from us loses that reply; appointment reminders and caregiver WhatsApp
alerts are not ported yet. Details: `docs/java-whatsapp-replies.md`.
