-- Appointments: a doctor visit for one parent. The parent gets WhatsApp reminders (backend), the caregivers get a copy.
alter table public.appointments
  add column location text check (location is null or char_length(location) <= 200),
  add column remind boolean not null default true,                 -- send WhatsApp reminders to the parent
  add column reminded_day_before_at timestamptz,                    -- set by the backend only, one reminder each
  add column reminded_morning_at timestamptz,
  add constraint appointments_doctor_len check (char_length(doctor_name) between 1 and 100),
  add constraint appointments_notes_len check (notes is null or char_length(notes) <= 500);

-- Caregivers may edit the visit, never the reminder bookkeeping.
revoke update on public.appointments from authenticated;
grant update (doctor_name, datetime, notes, location, remind) on public.appointments to authenticated;

-- Moving the visit to another time starts the reminders afresh.
create function public.appointments_reset_reminders() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.datetime is distinct from old.datetime then
    new.reminded_day_before_at := null;
    new.reminded_morning_at := null;
  end if;
  return new;
end $$;
create trigger appointments_reset_reminders before update on public.appointments
  for each row execute function public.appointments_reset_reminders();

create index appointments_upcoming on public.appointments (datetime) where reminded_morning_at is null;
