# Phone OTP login (over WhatsApp)

Login by phone number, with the one-time code delivered over **WhatsApp** via the self-hosted WA-AKG
gateway — the same gateway Chhaon already uses for reminders, and the same approach as Smart Gurukul.
No paid SMS provider, no per-message cost.

## How it works

The phone-OTP flow itself is **Supabase Auth**, unchanged and already built in the web app:

- `web/src/App.tsx` → `supabase.auth.signInWithOtp({ phone })` then `verifyOtp({ phone, token, type: "sms" })`.
- Supabase generates the code, rate-limits requests, and verifies it. The Java backend is auth-agnostic
  (`SupabaseJwtVerifier` just checks the signed JWT), so nothing changes there for login.

The only thing we replace is **delivery**. Instead of Supabase calling Twilio/MessageBird, it calls a
**Send-SMS auth hook** on our backend, which relays the code over WhatsApp:

```
Supabase Auth  --(Send SMS hook, signed)-->  POST /hooks/supabase-sms (Java)  --> WA-AKG gateway --> parent's WhatsApp
```

- `SendSmsHookController` (`/hooks/supabase-sms`) verifies the Standard-Webhooks signature
  (`StandardWebhookSignature`, fails closed), reads `{ user.phone, sms.otp }`, and sends
  `MessageTexts.loginOtp(code)` through `WhatsAppSender`.
- Public in `SecurityConfig` (a hook is not user-authenticated); authenticated by the signature instead.
- Nothing from the request is logged (it holds a phone number and a live code).

## Turning it on

### 1. Supabase dashboard (hosted project)
Authentication → **Hooks** → **Send SMS hook**:
- Type: **HTTPS**
- URL: `https://api.chhaon.site/hooks/supabase-sms`
- Copy the **secret** it shows (looks like `v1,whsec_…`).

Also make sure phone sign-ups are enabled (Authentication → Providers → Phone).

### 2. Backend secret
Add GitHub Actions secret **`SUPABASE_SEND_SMS_HOOK_SECRET`** = the secret from step 1.
The backend deploy writes it into `/etc/chhaon/backend.env`
(`app.supabase.send-sms-hook-secret`). Blank ⇒ `/hooks/supabase-sms` refuses everything (503).

The WhatsApp gateway must already be configured (`WHATSAPP_GATEWAY_URL`, `WHATSAPP_GATEWAY_TOKEN`,
`WHATSAPP_SENDER_SESSION_NAME`) — the same env the reminders use.

### 3. Web app
Set the GitHub Actions **variable** `VITE_PHONE_LOGIN` to `true` (anything but `false`), then redeploy
the web app. The login screen then shows the phone/email toggle and defaults to phone.

## Local dev
`supabase/config.toml` keeps the `[auth.sms.test_otp]` map (`919999999999 → 123456`), so no delivery is
needed locally. To exercise the real WhatsApp path locally, run the backend on `:8080`, set its secret,
and uncomment the `[auth.hook.send_sms]` block in `config.toml` (it points at `host.docker.internal:8080`).

## Notes
- The login message is bilingual (English + Hindi): at login we don't yet know the user's language.
- Fail-soft vs fail-hard: the gateway dry-runs when unconfigured, but for login we treat a real send
  failure as a 500 so Supabase surfaces "couldn't send, try again" to the user.
- The user's phone must be on WhatsApp. If a pilot family's login number is not on WhatsApp, fall back
  to email login (the toggle stays available) or wire a real SMS provider for that case.
