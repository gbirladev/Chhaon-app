# Who gets the reminder (person / me / someone else)

Step 2 of `docs/use-cases.md` (decision 5), branch `reminder-receiver`. Also delivers Dr. Birla's "home helper" idea.

Per person the family chooses who gets the WhatsApp reminders:

| Choice | `reminder_to` | Number (`whatsapp_phone`) | Greeted as |
|---|---|---|---|
| The person themself (default, every existing profile) | `person` | the person's own | "Namaste Papa" (exactly as before) |
| Me | `caregiver` (+ `reminder_user_id`) | typed in the form, else the caller's login number, else their confirmed alert number | caregiver's first name ("Namaste Ganesh") |
| Someone else (maid, attendant, Nani, nanny, husband ...) | `helper` (+ `reminder_name`, `reminder_relation`) | the helper's | "Namaste Sunita ji" |

## Data model

Migration `supabase/migrations/20261105000044_reminder_receiver.sql` (idempotent). **Deploy order: paste it into the Sydney SQL
editor BEFORE deploying the backend** (the backend validates its tables at start-up). Existing rows become `person`: nothing changes
for them.

* `whatsapp_phone` keeps its meaning: **the number that receives the reminders** (and whose replies count). So reply routing,
  STOP, `phone_opt_outs` and consent stay per NUMBER, unchanged.
* `reminder_to text not null default 'person'` (person | caregiver | helper), `reminder_name` (≤ 60), `reminder_relation` (≤ 30, app
  only, never sent on WhatsApp), `reminder_user_id` (→ profiles, on delete set null).
* Checks: the "Me" profile (`is_self`) is always `person`; a helper needs a name.
* Consent: the reset trigger now also fires when `reminder_to` changes, so switching who gets the reminders asks that number again
  (renaming the same helper does not).
* If the family member who got the reminders deletes their account, the person goes back to `person` without a number (their number
  must not keep receiving reminders about someone else).
* `guard_self_profile` (migration 0037) refused your own number on a parent's profile as a typing mistake. It is now allowed when you
  choose "Me" for that parent (`reminder_to = caregiver`); still refused otherwise.

API (`/api/v1/recipients`, create + update): new optional fields `reminderTo`, `reminderName`, `reminderRelation`; the response adds
those plus `reminderUserId`. On update, a missing `reminderTo` keeps what is stored (older app versions). A sibling saving the form with
`caregiver` and the same number does not take the reminders over. 400 for an unknown value, a helper without a name, or "Me" without
any number.

## What is sent

`ReceiverTexts` (third person, always names the person, keeps the pill-look line), used only when `reminder_to <> person`:

* Reminder: "Namaste Sunita ji 🙏 / Time for Papa's medicine 💊 *Metformin* / 🔵 Blue round tablet / Reply *1* or 👍 once you have given it ✅"
  (caregiver: "... once Papa has taken it").
* Consent: "Ganesh would like to send you reminders for Papa's medicines here on WhatsApp. Reply *1* to start ..." / caregiver:
  "You chose to get Papa's medicine reminders here on WhatsApp."
* Replies: "Noted for Papa: Metformin ✅", "Please reply 1 once you have given Papa the medicine.", consent yes names the person.
* Language: the profile's "Language" field is the language of the WhatsApp messages, for whoever receives them.
* Words understood as "given": diya, de diya, de di, दिया, दे दिया, दे दी, given, gave; "ho gaya / हो गया" only as the whole message
  ("kya ho gaya?" is not a confirmation).

Hindi texts: `docs/hindi-review.md` Part 7 (needs a native read).

## Daily check-in: decision

**No daily check-in when someone other than the person gets the reminders.** "Are you okay?" is a question to the person about
themself. A helper answering for the person's wellbeing is weaker information, and a maid who serves two people would get two extra
messages every morning; for "Me" it is pointless. The app says so ("There is no daily check-in"). A third-person check-in to the helper
("Is Papa okay today?") can be added later as an opt-in if families ask.

## Shared numbers (reply routing)

Nothing new in `ReplyRouter`'s rules: a reply goes to the most recent open reminder sent to that number, whoever it is for. Covered:

* one helper for Papa and Mummy: "diya" confirms the newest reminder (and names whose), the next "1" the other one;
* the caregiver's own medicines ("Me" profile) and Papa's reminders on one number;
* the caregiver's alert number = Papa's receiver number: a "1" answers the reminder (the alert question only wins when it was asked
  last, as before). STOP on that number stops both reminders and alerts (STOP belongs to the number).

## Family alerts

`AlertDelivery` looks up the receiver at delivery time and `AlertTexts.alert(..., receiver)` names who was reminded:
"Papa's medicine *Metformin* (due 8:00 AM) was not confirmed. The reminder went to Sunita (maid)." (to the caregiver who got it: "went to
you"). STOP / "no" are reported as the helper's ("Sunita (maid), who gets Papa's reminders, stopped ..."). Refill alerts are unchanged.

## App

* Add/edit person: "Who gets the WhatsApp reminders?" chips (Papa / Me / Someone else; a fourth chip with the name when another family
  member gets them), short explanation, helper name + relation, the number label follows the choice. A change of receiver sends a new
  consent request.
* Today: the consent card names the receiver ("Waiting for Sunita (maid) to agree"); a one-line note "WhatsApp reminders for Papa go to
  Sunita (maid). There is no daily check-in."; no check-in card; the "Call" buttons only dial the person's own number.
* Family screen: the same note. Emergency card: a helper's number is shown as "Gives the medicines", never as the parent's phone.

## Not done / known limits

* "Me" copies the caregiver's number at save time; if they later change their login number the reminders keep going to the old one
  until the form is saved again.
* The in-app notification list (bell) does not yet say who was reminded (the WhatsApp alert does).
* Care types (`care-type` branch): texts are written for an older parent; TODO in `ReceiverTexts` for child / pregnancy wording.

## R2: what the receiver reads (2026-11-05, branch `r2-receiver-texts`)

* **Dose in every reminder** (person, helper, "Me", pregnancy, photo caption): a line "Dose: *2.5 ml*" / "मात्रा: *2.5 ml*" from
  `medicines.dosage`. Only the unit word is translated (`MessageTexts.localizeDose`, same units as the web's `localizeDose`: tablet(s)
  -> गोली/गोलियाँ, capsule -> कैप्सूल, ml/mg stay). No dose stored = no line. A "dose" with a run of 6+ digits (a phone number) is never sent.
  Doses are never computed or changed.
* **Pregnancy week, decision:** said ONCE a week, in the first dose reminder sent on the day a new week starts ("Week 24 starts today 💛");
  every other reminder and the daily check-in leave it out (`SchedulerService#weekToSay`). Chosen over "check-in only" because the
  pregnancy check-in is expected to become optional.
* **"Me" Hindi:** "जब Papa दवा ले लें, तो *1* या 👍 भेजें ✅" (was the ungrammatical "Papa के दवा लेने के बाद").
* **Receiver language:** for a helper or a family member the texts use `coalesce(to_jsonb(r)->>'reminder_language', r.language)`
  (`ReceiverTexts.languageSql`), in the scheduler, the consent request and the replies; works before that column exists.
* **Family alerts name who to call** ("You may want to call Kamla (Nani)." / "call Papa"), never "them", never a number. A missing name
  says "someone in your family", never "your parent".
* **Preview WhatsApp** (`GET /api/v1/recipients/{id}/whatsapp-preview`, `WhatsAppPreviewController`): read-only, family members only, no
  phone numbers. Returns who receives ("Kamla (Nani)"), their language, consent state, and the exact texts built by the same code as the
  real send: consent request, next dose reminder (its real time, dose, pill look, whether the photo goes along), daily check-in (only when
  one is sent), a child's next vaccine message, and an example family alert (in the caller's language). Plus the reminder text of every
  active medicine, used for the last 3 days of real history. The web screen (`web/src/screens/WhatsAppPreview.tsx`) shows them as chat
  bubbles, no fake buttons.
* **Vaccine WhatsApp** to the child's reminder receiver (`VaccineReminderService#sendWhatsApp`): see that class and migration
  `20261105000051_vaccine_whatsapp.sql` (deploy it before or after the backend; without it no vaccine WhatsApp is sent).
