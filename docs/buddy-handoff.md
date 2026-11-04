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

## Step by step (do in this order)

**A. Get the secret.** The product owner generates it (`openssl rand -hex 32`) and sends it to you privately. Keep it in a
password manager. If you would rather generate it yourself, run the same command and send the value back to the product owner.

**B. Put it in the backend.** On the server, edit the backend env file (the one the service reads, built from
`api-java/deploy/aws/backend.env.example`) and add the line `WHATSAPP_WEBHOOK_SECRET=<secret>`. While you are there, check
that `WHATSAPP_GATEWAY_URL`, `WHATSAPP_GATEWAY_TOKEN` and `WHATSAPP_SENDER_SESSION_NAME` have values and that
`CHHAON_SCHEDULER_ENABLED` is false or absent. Restart the backend service. (This setting only takes effect once the new code
is deployed, see item 3. Before that, it is harmless.)

**C. Put it in the gateway.** Open the gateway dashboard, find the webhook settings of the sending session, and set:
URL `https://api.chhaon.site/webhooks/whatsapp-gateway`, secret = the same value, events `message.received` and
`message.status`. Save. Make sure the session shows as connected.

**D. Check the webhook after the new code is deployed** (from any terminal; replace `<secret>`):

```
URL=https://api.chhaon.site/webhooks/whatsapp-gateway
# 1. No signature. Expect HTTP 401 {"status":"bad signature"}  (503 means the secret is not set on the server yet)
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$URL" -H 'Content-Type: application/json' -d '{"event":"test"}'
# 2. Correct signature on a harmless test event. Expect HTTP 200 {"status":"ignored:event"}
BODY='{"event":"test"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac '<secret>' | sed 's/^.* //')
curl -s -w "\n%{http_code}\n" -X POST "$URL" -H 'Content-Type: application/json' -H "X-Webhook-Signature: sha256=$SIG" -d "$BODY"
```

If 1 gives 401 and 2 gives 200, the secret matches and the webhook works. A 200 on test 1 should never happen.

**E. Tell the product owner** the results of D and whether the session is connected.

## 6. After you have done 1 and 2

Tell the product owner. They will push, and then the two of you do the real-phone test and the scoped single-parent tick from
`docs/TODO.md` ("Switch-on checklist"), and only then switch the scheduler on.

Known gaps on the Java side: a sender the gateway cannot turn into a phone number (an `@lid` without a number) is ignored, even
if it says STOP; the gateway never retries, so a 500 from us loses that reply; appointment reminders and caregiver WhatsApp
alerts are not ported yet. Details: `docs/java-whatsapp-replies.md`.
