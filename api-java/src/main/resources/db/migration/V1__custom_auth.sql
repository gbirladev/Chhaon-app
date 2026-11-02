-- Custom-JWT auth: decouple profiles from Supabase auth.users and own credentials ourselves.
--
-- Previously profiles.id was an FK to auth.users(id) (Supabase Auth). The Spring Boot backend
-- now generates the user id and stores the password, so that coupling must go. Existing rows
-- keep their current ids; existing users will need a password set (migration/one-time reset)
-- before they can log in via the new flow.

-- 1) Drop the FK to Supabase's auth.users. (Safe no-op if already dropped.)
ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS profiles_id_fkey;

-- 2) Password credentials, keyed by profiles.id.
CREATE TABLE IF NOT EXISTS public.user_credentials (
    user_id       uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.user_credentials IS
    'Caregiver password hashes (BCrypt). Owned by the Spring Boot backend; replaces Supabase Auth.';
