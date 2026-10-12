# Privacy operations (DRAFT, for the operator of Chhaon)

What a person can do, and what we do when they ask. Policy text lives in `web/src/content/legal.ts` (English + Hindi, DRAFT for lawyer review; bump `POLICY_VERSION` when the meaning changes so everyone is asked to agree again).

| Request | How | What happens |
|---|---|---|
| Copy of data | App: Family > Privacy & data > Download my data (`GET /privacy/export`) | JSON of everything the caregiver can access. No secret links, tokens or file bytes. |
| Correction | Edit in the app (parent, medicines, appointments, report values) | |
| Erase one parent | Privacy & data > Delete data (owner only; `POST /privacy/recipients/{id}/delete`) | Stored files (medicine photos, reports) are deleted first, then the parent row, which cascades to medicines, schedules, doses, check-ins, reports, values, appointments, emergency links. |
| Erase account | Privacy & data > Delete my account, type DELETE (`POST /privacy/delete-account`) | 1. files of circles where the caregiver is the only member, 2. DB function `erase_caregiver` (sole circles deleted; shared circles keep going, longest-standing member becomes owner if no owner is left; alert number cleared; notifications deleted), 3. auth user deleted. Every step is safe to repeat, so a failed attempt can simply be retried. |
| Parent withdraws | Parent replies STOP on WhatsApp | We stop at once. Only the phone number stays on `phone_opt_outs` so we never message it again; START re-enables. Changing a parent's number resets consent. |
| Caregiver alert STOP | Same STOP/START flow for the alert number | |

Things to remember
- Content a leaving caregiver added to a circle that continues (reports, appointments) stays with that circle; their name link is removed (`uploaded_by` / `created_by` become null).
- Backups: deleted data can remain in Supabase backups until they expire. State the real period in the policy.
- Never log health data or phone numbers. Use `app.privacy.mask_phone` for any phone in a log line.
- Grievance contact, company name and legal wording are placeholders in `legal.ts` until the lawyer review.
- Breach: DPDP requires telling the affected users and the Data Protection Board. Keep an incident contact list.
