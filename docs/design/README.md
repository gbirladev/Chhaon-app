# Figma export (reference only)

Vite + React prototype of the CareLink UI (11 screens, hardcoded demo data). Not part of the build.
Port screens into `web/` as real routes backed by Supabase. Decisions so far:
- Roles: owner + member only (no view-only in v1).
- Emergency card link: no expiry, revocable by the caregiver; share the link, not the health text.
- Drop demo shortcuts (demo login link, fixed OTP, localStorage state, hotlinked stock photo).
