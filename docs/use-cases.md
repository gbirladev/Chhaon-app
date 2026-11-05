# Use cases: Older, Pregnant, Child (research and decisions, 2026-11-05)

Why: the app speaks to every family as "your parent". A real family (the owner's brother-in-law, for his wife's pregnancy) uses it and
it does not fit them. Dr. Deepak Birla also named pregnancy and children as strong niches. Decision: keep ONE app and add a
"care type" per person. Elderly care stays the main focus.

## The model

When adding (or editing) a person, the family picks who they care for:

| Care type | Who gets the WhatsApp reminder | Tone | Daily check-in | Extra |
|---|---|---|---|---|
| **Older** (default, every existing profile) | The person (as today), later optionally a helper | "Namaste Papa 🙏" | Yes ("are you okay?") + family alert | (as today) |
| **Pregnant** | The woman herself if she wants; otherwise the caregiver uses the app only (later: the caregiver or a helper gets the reminders) | "Good morning Shivani ☀️" | Optional, off by default, gentler ("How are you feeling today?") | Due date, pregnancy week card, suggested usual checks, supplement quick-add |
| **Child** | NEVER the child: the person who gives the medicine (mother, Nani, nanny) | "Time for Aarav's syrup" | No | Date of birth, vaccination schedule (babies), courses with an end date |

Everything else is shared: medicines, refills, family circle, family WhatsApp alerts, reports wallet, emergency card, appointments.
The existing "Me" profile (`is_self`) stays as it is.

## Research notes (sources at the end)

- **Pregnancy checks in India:** MoHFW asks for a minimum of 4 antenatal visits (one in the third trimester, 28-34 weeks, by a medical
  officer); PMSMA adds a free specialist check-up on the 9th of every month in the 2nd/3rd trimester. I did NOT find an official
  per-visit test list, so the in-app list is "common checks, confirm with your doctor", editable, and must be read by a
  gynaecologist before families are told about it.
- **Supplements:** Anemia Mukt Bharat: iron + folic acid daily from the 4th month, at least 180 days in pregnancy and 180 days after
  delivery (tablet strength differs between national and state pages). NHM: calcium (500 mg + vitamin D3) twice daily from the
  2nd trimester. The app offers the NAMES as quick-add only; dose and timing are always typed from the prescription.
- **Vaccines (children):** the Indian Academy of Pediatrics (IAP ACVIP) timetable covers 0-18 years (latest found: 2023 edition,
  Indian Pediatrics Feb 2024); the government's national schedule differs in places. The in-app table must be checked by a
  paediatrician against the current IAP timetable before release, and every date is editable ("as advised by your doctor").
- **Children's data (DPDP Act + Rules notified Nov 2025, main duties apply about 18 months later):** verifiable consent of a parent or
  lawful guardian for anyone under 18; no tracking, behavioural monitoring or targeted ads directed at children, whatever the consent.
  For Chhaon: the logged-in caregiver confirms "I am the parent / legal guardian" when adding a child; no ads or analytics on child
  profiles; a child's data is only visible inside the family circle.
- **Pregnancy law:** nothing that could hint at the sex of the baby (PCPNDT Act). No answers to "is this normal?".
- **Competitors:** Healofy (week-by-week tracker, tips, diet, community, 20M+ users), GynoSakhi, Motherly (Indian vaccine reminders),
  BabyCenter, Practo. They are content and community apps on the woman's phone. I found none that sends the reminder on WhatsApp to the
  person at home or lets the husband and both grandmothers share one view. That is Chhaon's angle; do NOT build content, diet or community.

## Decisions

1. One `care_type` per person: `older` (default) | `pregnant` | `child`. Existing profiles become `older`; nothing changes for them.
2. Pregnancy needs a **due date** (doctors give it; easier for the husband than the last period date). Week = 40 - weeks until due.
3. Children need a **date of birth**. (The profile stores only an age today; DOB is needed for vaccines.)
4. Suggested checks and vaccines are **suggestions the family taps to add** as appointments, never automatic, always editable.
5. "Who gets the reminder" (person / me / someone else) is step 2 and also delivers Dr. Birla's home-helper idea.
6. Out of scope: diet, feeds, diapers, sleep, community, content, dose calculation, warning-sign advice (a "when to call the doctor"
   card only after a gynaecologist writes/approves the text).

## Build order

1. **Care type + Pregnant** (this step): the choice when adding/editing a person, due date, pregnancy week card on Today, pregnancy
   wording in the app and in WhatsApp texts, supplement quick-add, suggested usual checks (labelled for doctor review).
2. **Who gets the reminder** (person / me / helper), for all care types.
3. **Child**: date of birth, guardian confirmation, vaccination schedule, reminders only to the adult who gives the medicine.
4. Before telling real families: gynaecologist reads the pregnancy checks; paediatrician reads the vaccine table; native Hindi read.

## Step 1 as built (2026-11-05, branch `care-type`)

- **NEEDS GYNAECOLOGIST REVIEW before families are told about it:** the "Usual checks" list (names and week windows) and the
  supplement names, both in ONE file: `web/src/lib/pregnancyChecks.ts`. Shown as "Common checks — confirm with your doctor"; nothing is
  added automatically ("+ Add" only opens a new appointment with the name in the notes and a suggested date for the family to edit).
- **Baby's approximate size (added by the owner, branch `r5-baby-size`), ALSO NEEDS GYNAECOLOGIST REVIEW before release:** one line on
  the week card, e.g. "Baby is about the size of a corn cob (bhutta) · about 30 cm, 600 g", with a small 3D icon (Microsoft Fluent
  Emoji, MIT) and "Approximate — every baby grows differently." Weeks 8-40 only; no line without a due date. All numbers and the
  comparisons are in ONE file: `web/src/lib/babySize.ts` (sources in its header). Size only: nothing about the sex of the baby,
  no "normal / small / big", no advice.
- Care type is chosen on the add/edit person form. **Child** is shown as "coming soon" (greyed, not selectable) so families see it is
  planned, and the backend refuses `child` until step 3. The caregiver's own profile ("Me") has no choice and stays older.
- **Due date**: optional for a pregnancy (the week card waits for it). Accepted from about 2 months past to about 10 months ahead
  (60 / 300 days, on her calendar), checked only when it changes so an old date never blocks other edits. Switching away from
  pregnant clears it (the database also refuses a due date on a non-pregnancy), so a hidden date can never linger.
- **Week** = completed weeks from the due date (280-day term), same sum in Java (`recipient/Pregnancy.java`) and web
  (`web/src/lib/pregnancy.ts`). Trimesters: weeks 0-13, 14-27, 28+. No number shown before week 1 or after week 42.
- **Daily check-in for a pregnancy:** kept ON (a per-person on/off is not built yet) but with the gentle wording
  "How are you feeling today?" and the week; the family is still alerted when there is no reply. Update (round 2, migration 0050): a
  per-person switch now exists; a NEW pregnancy starts with it off, existing ones keep it on (see docs/reminder-receiver.md, "Round 2").
- WhatsApp texts for a pregnancy greet by the time of day on her clock and keep the usual replies (*1* taken / fine, *2* call me).

## Vaccines: "which ones were already given?" (2026-11-05, branch `f1-vaccines-given`, migration 0046)

- A child set up after birth (say at 5 months) has many doses whose suggested date has passed; most were given in hospital or at the
  clinic. Such a dose is NOT called overdue: the Vaccines screen lists it under "Earlier vaccines" as "Suggested for <date> · Already
  given?" and offers the step "Which ones were already given?" (opens by itself right after the plan is made, and from the screen while
  such doses exist): checkboxes grouped by age, all unticked, "Tick all up to <age>", "Tick what is on the vaccination card".
- **Save**: ticked = given, unticked = "not given yet" (only these say "Overdue since <date>"). **Not sure**: saves only what is ticked
  and leaves the rest to ask again later. "Mark as not done yet" on a past dose also counts as "not given yet".
- **Given, date not recorded**: stored as `given_on` = the plan date (a placeholder, so every "is it given?" check keeps working) plus
  `given_date_unknown = true`; the app shows "Given (date not recorded)" and never shows the placeholder as the date given. The family can
  add the real date later (clears the flag). `not_yet_given_at` records the "not given yet" answer.
- API: `POST /api/v1/recipients/{id}/vaccines/given-earlier` `{given: [ids], notGivenYet: [ids]}`; each row of the plan now also has
  `givenDateUnknown` and `askIfGiven` (the `status` values are unchanged: a past open dose is still `overdue`, so check `askIfGiven`
  before calling it overdue anywhere).

## Sources
- PMSMA (MoHFW): https://pmsma.mohfw.gov.in/about-scheme/ , NHM PMSMA guideline PDF:
  https://nhm.gov.in/images/pdf/programmes/maternal-health/guidelines/Pradhan_Mantri_Surakshit_Matritva_Abhiyan.pdf
- Anemia Mukt Bharat: https://en.vikaspedia.in/health/health-campaigns/anemia-mukt-bharat ; calcium in the NHM ANC package:
  https://www.frontiersin.org/articles/10.3389/fnut.2022.894245/full
- IAP schedule: https://www.indianpediatrics.net/feb2024/113.pdf
- DPDP Rules and children's data: https://www.scconline.com/blog/post/2025/12/26/digital-personal-data-protection-rules-2025-key-highlights/ ,
  https://www.medianama.com/2025/01/223-data-protection-rules-2025-children-data-india/
- Competitors: https://appgoblin.info/apps/com.healofy , https://appgoblin.info/apps/com.gynosakhi , https://apps.apple.com/app/id6746041100
