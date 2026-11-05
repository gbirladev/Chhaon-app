-- "Who and how" (round 2, R1): more relations, a per-person daily check-in switch, and the language of the person who gets the
-- reminders when that is not the person themself (a helper or a family member). See docs/reminder-receiver.md ("Round 2").
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend: the backend maps the two new
-- columns and validates its tables at start-up, so it would refuse to start without them. Safe to run twice.
--
-- 1. Relations: son, daughter, grandson, granddaughter, wife, husband (a child or a pregnancy is not a "father").
--    'spouse' stays valid for existing rows; the app now offers wife / husband instead.
-- 2. checkin_enabled: the daily "How are you?" check-in for this person. Default true (every existing profile keeps it).
--    A NEW pregnancy is created with it off (the backend does that). Existing pregnancy rows are left as they are (on): the
--    families who already get it are not surprised by a message that silently stops; they can switch it off in the form.
--    The scheduler reads it defensively (to_jsonb(r)->>'checkin_enabled'), so the scheduler works before and after this file.
-- 3. reminder_language: 'en' | 'hi' | null. The language of the WhatsApp messages for the receiver when reminder_to is
--    caregiver or helper (a maid who reads Hindi while Papa's profile says English). null = use the person's language.
--    Message code picks it with coalesce(to_jsonb(r)->>'reminder_language', r.language) (only when reminder_to <> 'person').

-- ---------------------------------------------------------------- 1. relations
alter table public.care_recipients drop constraint if exists care_recipients_relation_check;
alter table public.care_recipients
  add constraint care_recipients_relation_check
  check (relation in ('father', 'mother', 'grandfather', 'grandmother', 'father_in_law', 'mother_in_law',
                      'spouse', 'uncle', 'aunt', 'brother', 'sister', 'other', 'self',
                      'son', 'daughter', 'grandson', 'granddaughter', 'wife', 'husband'));

-- ---------------------------------------------------------------- 2. daily check-in switch
alter table public.care_recipients add column if not exists checkin_enabled boolean not null default true;
comment on column public.care_recipients.checkin_enabled is
  'Daily "How are you?" check-in on WhatsApp for this person (only sent when the person gets the reminders themself). New pregnancies start off.';

-- ---------------------------------------------------------------- 3. the receiver's own language
alter table public.care_recipients add column if not exists reminder_language text;
alter table public.care_recipients drop constraint if exists care_recipients_reminder_language_known;
alter table public.care_recipients add constraint care_recipients_reminder_language_known check (
  reminder_language is null or reminder_language in ('en', 'hi')
);
comment on column public.care_recipients.reminder_language is
  'Language of the WhatsApp messages for a helper / family member who gets the reminders (en | hi); null = the person''s language';
