-- WhatsApp vaccine reminders to the adult who receives a child's reminders (R2; api-java .../child/VaccineReminderService.java).
--
-- The in-app reminder keeps its own bookkeeping (child_vaccines.reminded_due_date, migration 0045). The WhatsApp message goes out twice
-- (about 3 days before the due date and on the day itself, both in the child's daytime), so it needs two more markers in the same
-- style: "the due date this message was already sent for". A changed date is reminded again; overlapping runs never send twice
-- (claimed with a guarded UPDATE before sending, handed back when the send fails).
--
-- Idempotent. Deploy: paste into the SQL editor before (or after) the backend: without these columns the backend simply sends no
-- vaccine WhatsApp (the in-app reminder is unaffected). Backend-only columns: no new grants for signed-in users (the column-level
-- INSERT/UPDATE grants of 0045 do not include them; service_role already has every privilege on the table).

alter table public.child_vaccines add column if not exists whatsapp_before_for date;
alter table public.child_vaccines add column if not exists whatsapp_day_for date;

comment on column public.child_vaccines.whatsapp_before_for is
  'The due date the "due in a few days" WhatsApp to the reminder receiver was sent for (backend bookkeeping)';
comment on column public.child_vaccines.whatsapp_day_for is
  'The due date the "due today" WhatsApp to the reminder receiver was sent for (backend bookkeeping)';
