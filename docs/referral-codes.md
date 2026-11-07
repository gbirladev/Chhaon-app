# Referral codes for everyone

Every caregiver gets a referral code to share Chhaon, and can see how many families joined with it. Creators use the same feature.

## What people see
- **Family > More > Invite friends:** their code (made from the first name the first time, e.g. ANANYA27), the link
  `chhaon.site/?ref=CODE`, **Share on WhatsApp** (opens the person's own WhatsApp, never Chhaon's sending number), **Copy link**,
  and "N families joined with your code".
- **Choose a different code:** 4-15 letters or digits. A taken or reserved code gets 3 free suggestions. At most 3 changes;
  old codes keep counting (shared links never break).
- **First Add-your-parent form:** an optional "Referral code" box under "How did you hear about Chhaon?". Pre-filled from a
  `?ref=` link; shown as done when the account already has a code. An unknown code or the person's own code is refused.

## Rules
- First code wins; only accounts younger than 7 days (unchanged, `MeController.REFERRAL_WINDOW`).
- Campaign codes (doctor D001 / IND001 / DEL042, creator CRE022, anything with symbols) are not in the table and are accepted as
  before. User codes can never look like them (`^[A-Z]{1,4}[0-9]{3,4}$` is refused), so the two never collide.
- Reserved: CHHAON (and anything containing it), ADMIN, FREE, DOCTOR, SUPPORT, HELP, TEST, NULL.
- Nobody reads codes through Supabase (RLS, no grants); the admin's Pilot stats show sign-ups per code with the owner's name
  for user codes.

## Where
- SQL: `supabase/migrations/20261107000061_referral_codes.sql` (table `referral_codes`, `admin_referral_stats()` with owner).
  **Sydney: paste it in the SQL Editor first, then push.** Without it the backend behaves as before.
- Backend: `ReferralCodeService`, `ReferralCodeController` (`/api/v1/me/referral`, `/check`, PUT, `/api/v1/referral/validate`),
  check in `PATCH /api/v1/me`.
- Web: `screens/InviteFriends.tsx`, `ReferralCodeField` in `screens/Referral.tsx`, `lib/referral.ts`.
- Tests: `supabase/tests/referral_codes.test.sql`, `ReferralCodeApiTest`.
