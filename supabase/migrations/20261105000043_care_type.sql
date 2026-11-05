-- Who the family cares for (docs/use-cases.md, step 1): an older parent (as before), a pregnancy, or (later) a child.
-- Every existing profile becomes 'older', so nothing changes for current families. A pregnancy may carry the due date the
-- doctor gave; the app shows the pregnancy week from it (in the person's own time zone) and words its texts for a pregnancy.
-- The due date belongs to a pregnancy only: switching the profile away from 'pregnant' clears it (the backend does this,
-- the check below makes sure). The "sane window" (about 10 months ahead, 2 months past) is checked by the backend, not
-- here, because it depends on today's date.
-- 'child' is accepted by the database already but the app does not offer it yet (step 3: date of birth, guardian, vaccines).
-- Saved by the Java backend (POST/PUT /api/v1/recipients).
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that maps these columns:
-- the backend validates its tables at start-up and would refuse to start without them. Safe to run twice.

alter table public.care_recipients add column if not exists care_type text not null default 'older';
alter table public.care_recipients add column if not exists due_date date;

alter table public.care_recipients drop constraint if exists care_recipients_care_type_known;
alter table public.care_recipients add constraint care_recipients_care_type_known check (
  care_type in ('older', 'pregnant', 'child')
);

alter table public.care_recipients drop constraint if exists care_recipients_due_date_pregnant_only;
alter table public.care_recipients add constraint care_recipients_due_date_pregnant_only check (
  due_date is null or care_type = 'pregnant'
);

comment on column public.care_recipients.care_type is 'older (default) | pregnant | child: who the family cares for; changes the wording in the app and on WhatsApp';
comment on column public.care_recipients.due_date is 'Expected due date given by the doctor (pregnant only); the pregnancy week is counted back from it';
