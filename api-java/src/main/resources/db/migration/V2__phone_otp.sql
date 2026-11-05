-- Phone-OTP login, owned by the Spring Boot backend (replaces Supabase Auth / GoTrue for login).
--
-- Login now works like Smart Gurukul: the backend generates a one-time code, stores only its BCrypt
-- hash, delivers it over WhatsApp, verifies it, and mints a session token. Identity is still keyed by
-- profiles.id (V1 already dropped the FK to Supabase auth.users); a profile is resolved by phone, and a
-- brand-new phone creates a profile on first verify.

-- 1) One-time codes. We keep the hash only, never the plaintext code. One row per request; verify reads
--    the latest unconsumed row for the phone.
CREATE TABLE IF NOT EXISTS public.otp_codes (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    phone           text        NOT NULL,          -- normalised E.164 (+91XXXXXXXXXX)
    code_hash       text        NOT NULL,          -- BCrypt hash of the code
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz,                   -- set on success or when burned
    failed_attempts int         NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.otp_codes IS
    'Phone-login one-time codes (BCrypt-hashed). Owned by the Spring Boot backend; replaces Supabase Auth login.';

-- Verify loads the newest unconsumed code for a phone; the rate-limiter counts recent rows per phone.
CREATE INDEX IF NOT EXISTS idx_otp_codes_phone_created ON public.otp_codes (phone, created_at DESC);

-- 2) A phone must resolve to exactly one profile at login. Enforce uniqueness on non-null phones.
--    NOTE: this fails if the current data has duplicate phones. Clean duplicates first:
--      select phone, count(*) from public.profiles where phone is not null group by phone having count(*) > 1;
--    (The pilot's duplicates are test profiles on numbers the owner controls.)
CREATE UNIQUE INDEX IF NOT EXISTS uq_profiles_phone
    ON public.profiles (phone) WHERE phone IS NOT NULL;
