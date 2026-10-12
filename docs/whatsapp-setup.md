# WhatsApp setup (Meta Cloud API)

The backend (`api/`) already sends templates, receives button taps, handles STOP/START and verifies webhooks.
Without credentials it runs in **dry-run mode**: nothing is sent, messages are only logged (phone numbers masked).
This guide connects it to a real WhatsApp Business account. **Template approval takes days. Submit them first.**

## 1. Account and number
1. Create a Meta Business account and a **WhatsApp Business Account** at business.facebook.com.
2. Create a Meta developer app (type **Business**), add the **WhatsApp** product.
3. For testing, use Meta's free **test number** (you can message up to 5 verified numbers).
   For the pilot, add a real number. It cannot be used in the normal WhatsApp app at the same time.
4. Note down: **Phone number ID**, a **permanent access token** (System User token, not the 24-hour temporary one),
   and the **App Secret** (App settings, Basic).

## 2. Environment variables (`api/.env`)
```
WHATSAPP_ACCESS_TOKEN=<system user token>
WHATSAPP_PHONE_NUMBER_ID=<phone number id>
WHATSAPP_APP_SECRET=<app secret>          # verifies X-Hub-Signature-256; the webhook refuses to run without it
WHATSAPP_VERIFY_TOKEN=<any long random string you choose>
```
Setting both the token and the phone number id turns dry-run mode off.

## 3. Webhook
In the app's WhatsApp, Configuration:
- **Callback URL:** `https://<your-api-host>/webhooks/whatsapp` (must be public HTTPS)
- **Verify token:** the same `WHATSAPP_VERIFY_TOKEN`
- Subscribe to the **messages** field.

For local testing you need a public URL to your laptop (a tunnel such as ngrok or Cloudflare Tunnel).
Remember that this exposes the dev API while it runs.

## 4. Templates to submit
**No brand name in template text.** The sender shows the WhatsApp display name, so the bodies say "us" instead of a product name. This way a rename
does not force a re-approval. The `carelink_` prefix is only the internal template name and is never shown to users.
Each template exists in **English (`en`) and Hindi (`hi`) under the same name**. The names below are what the code sends.
Buttons are **Quick reply** buttons. When sending, the code attaches the payload to each button, and Meta returns it in the webhook.
Meta asks for an example value per `{{n}}`.

**The submit-ready texts are in `docs/whatsapp-templates.md`** (the same data is in `docs/whatsapp-templates.json`). There are 14 templates, each submitted
in English (`en`) and Hindi (`hi`) under the same name, category Utility. A test (`api/tests/test_template_pack.py`) checks that the pack matches what the code sends
(names, number of variables, buttons) and that every body follows Meta's rules (no variable at the start or end, one example per variable, short buttons).

How alerts to the caregiver work: the caregiver's own WhatsApp number gets alerts too, so it can reach them when the app is closed. **Double opt-in:** the app asks for a number,
we send `carelink_alert_optin`, and alerts start only when *that number* taps Yes. STOP from that number turns them off, START brings them back
(only if it had confirmed). A number that said No is never messaged again. Limits: 6 alerts per caregiver per hour, an alert older than 6 hours is
never sent late, a failed send is retried up to 3 times.

### Category and consent: read before submitting
- Reminders and check-ins should be submitted as **Utility**. Meta may reclassify a template it considers promotional.
- **Opt-in is the risk.** Meta's policy expects the person to have agreed to receive messages *before* the business messages them.
  The caregiver confirms the parent agrees (stored as `caregiver_attested_at`), and the consent template then asks the parent
  directly, but the template itself is a business-initiated message. Check this flow against Meta's current policy,
  and ask a health-tech lawyer, before launching publicly.
- Free-form replies (our "thank you" / "stopped" confirmations) are only allowed within 24 hours of the parent's last message.
  The code sends them only in direct reaction to a parent's message.

## 5. How the flows work
| Event | What happens |
|---|---|
| Caregiver saves a parent, or changes their number | App calls `POST /recipients/{id}/consent-request`. Parent gets the consent template. A changed number resets consent in the database. |
| Parent taps **Yes** | `consent_at` is set, the circle gets a `parent_consented` notification, and the parent gets a thank-you. |
| Parent taps **No**, or sends a short message containing **STOP** (also "Stop.", "please stop", "रुको", "बंद करो") | `opted_out_at` is set. No template is ever sent again (`can_message` is false). Caregivers are notified. |
| Parent replies **START** | Clears `opted_out_at`. If they had declined (No) and never agreed, START counts as agreeing. |
| Parent taps **Taken** | The dose event becomes `taken`. A late tap after `missed` still counts. |
| Parent taps **I'm okay / Call me** | The check-in becomes `replied`. **Call me** notifies the circle once. |
| Message delivery **fails** | The circle gets a `whatsapp_failed` notification (ids only). |

Safety rules built in: signatures are mandatory; every event id is stored first so Meta's retries never duplicate work;
a reply is accepted only from the parent's own number; messages are never sent without consent; and logs contain
no message text and only masked phone numbers.

## Abuse limits (built in)
- One consent request per phone number every 10 minutes, across all accounts; at most 10 requests per caregiver per day (`consent_requests` log, backend only).
- Names that go into templates are stripped of control characters and shortened to 40 characters.
- Webhook bodies over 1 MB are refused; malformed events are skipped (and never block a valid STOP in the same delivery).

## 6. Checking it
- `cd api && uv run pytest` replays realistic Meta webhooks against the local database (see api/tests).
- Verify the webhook handshake: `curl "http://127.0.0.1:8000/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=<token>&hub.challenge=123"` should print `123`.

## 7. Scheduler
The scheduler (`docs/scheduler.md`) sends reminders and check-ins, marks missed doses and delivers the caregiver alerts above as its last step.
