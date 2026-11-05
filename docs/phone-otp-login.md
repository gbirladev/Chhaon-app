# Phone-OTP login (owned by the Java backend)

Login is by **phone number + one-time code**, like Smart Gurukul. There is **no email login and no
Supabase Auth (GoTrue)**. Our Java backend generates the code, stores only its BCrypt hash, delivers it
over WhatsApp (WA-AKG gateway), verifies it, and mints the session token. Supabase remains only as the
database/storage underneath.

## How it works

```
web  --POST /api/v1/auth/otp/request {phone}-->  Java  --> WA-AKG gateway --> user's WhatsApp
web  --POST /api/v1/auth/otp/verify {phone,code}--> Java --> { token, userId, name, plan, isNew }
```

- `OtpController` / `OtpService` (`api-java/.../auth/otp/`): normalise the phone (`PhoneNormalizer`),
  rate-limit (cooldown 30s, ≤5/hour), generate a 6-digit `SecureRandom` code, store its BCrypt hash in
  `otp_codes` (migration `V2__phone_otp.sql`, 5-min expiry, ≤5 wrong guesses), send via `WhatsAppSender`.
  On verify, resolve the caregiver by `profiles.phone` or **create a profile** for a new number, then mint
  the token. Nothing logs the phone or the code.

### The session token (why the rest of the app keeps working)
The web app reads most data and all files **directly from Supabase** under RLS. So the token we return is
an **HS256 JWT signed with the Supabase project JWT secret** (`SUPABASE_JWT_SECRET`), shaped like a
Supabase access token (`sub`=profiles.id, `role`=`aud`=`authenticated`). It is accepted by **both**:
- Supabase PostgREST + Storage (so `supabase.from/rpc/storage` + RLS keep working), and
- the Java API (its `JwtAuthenticationFilter` already verifies such tokens via `SupabaseJwtVerifier`).

`SupabaseCompatibleTokenService` mints it. The web client hands it to supabase-js through the
`accessToken` provider (`web/src/lib/supabase.ts`); `web/src/lib/auth.ts` owns the token (localStorage),
the OTP calls, and logout. There are no refresh tokens yet, so the token is long-lived
(`app.supabase.token-expiry-minutes`, default 7 days); on expiry the user logs in again.

This shared JWT secret is the **only** remaining Supabase tie in auth. Removing it too (porting the whole
data + storage layer to Java) is a separate, larger project ("B2").

## Turning it on
1. **`SUPABASE_JWT_SECRET` must be set on the backend** (GitHub secret → `/etc/chhaon/backend.env`). Without
   it, login fails closed (`SupabaseCompatibleTokenService` disables itself; the rest of the API keeps
   serving) and the Java API can't verify our HS256 tokens. **Gating check before trusting B1:** confirm the
   Supabase project still accepts HS256 tokens signed with the legacy secret at PostgREST — mint a token and
   `curl {SUPABASE_URL}/rest/v1/profiles?select=id&limit=1` with `apikey:<anon>` + `Authorization: Bearer`.
2. Deploy the backend (migration `V2`, new `/api/v1/auth/otp/*` endpoints).
3. Deploy the web app.
4. **Data pre-reqs:** each existing caregiver needs a unique, personal `profiles.phone` or they can't log in
   (email login is gone). Clean duplicate phones first (the `V2` unique index needs it):
   `select phone, count(*) from profiles where phone is not null group by phone having count(*)>1`.

## Delivery still needs a working number
OTP codes go over the WhatsApp gateway. If its number is banned/unavailable, codes won't arrive — this
rewrite changes *who issues* the code, not *how it is delivered*. Local dev: `WhatsAppSender` dry-runs and
logs the code when the gateway isn't configured.

## Config (`application.properties`)
- `app.supabase.jwt-secret` (env `SUPABASE_JWT_SECRET`) — signs the session token.
- `app.supabase.token-expiry-minutes` (default 10080 = 7 days), `app.supabase.token-issuer` (`chhaon`).
- `app.otp.code-length` (6), `app.otp.expiry-minutes` (5), `app.otp.request-cooldown-seconds` (30),
  `app.otp.max-requests-per-hour` (5), `app.otp.max-failed-attempts` (5).

## Superseded
The earlier Supabase **Send-SMS auth hook** (`SendSmsHookController`, `/hooks/supabase-sms`) is no longer
used for login — Supabase Auth is out of the flow. The endpoint is harmless (fails closed) and can be
removed in a later cleanup.
