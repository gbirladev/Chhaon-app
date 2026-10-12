-- CareLink schema (step 1). All timestamps are timestamptz (UTC).
-- Parent-local times (time_of_day) are stored as plain `time` and interpreted
-- in care_recipients.timezone by the scheduler.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- enums
create type public.member_role as enum ('owner', 'member');
create type public.dose_status as enum ('pending', 'sent', 'taken', 'missed');
create type public.checkin_status as enum ('pending', 'sent', 'replied', 'missed');
create type public.report_ai_status as enum ('pending', 'processing', 'done', 'failed');
create type public.value_flag as enum ('normal', 'low', 'high', 'critical');

-- ---------------------------------------------------------------- helpers
create function public.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

-- ---------------------------------------------------------------- profiles
-- Caregiver users. One row per auth.users row (created by trigger).
create table public.profiles (
  id          uuid primary key references auth.users (id) on delete cascade,
  name        text,
  email       text,
  phone       text,
  country     text,
  timezone    text not null default 'Asia/Kolkata',
  language    text not null default 'en' check (language in ('en', 'hi')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create trigger profiles_updated_at before update on public.profiles
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------- circles
create table public.care_circles (
  id          uuid primary key default gen_random_uuid(),
  -- set null (not cascade) so deleting one sibling's account does not
  -- delete the circle for everyone else. Ownership transfer: later step.
  created_by  uuid references public.profiles (id) on delete set null,
  created_at  timestamptz not null default now()
);

create table public.care_circle_members (
  circle_id   uuid not null references public.care_circles (id) on delete cascade,
  user_id     uuid not null references public.profiles (id) on delete cascade,
  role        public.member_role not null default 'member',
  created_at  timestamptz not null default now(),
  primary key (circle_id, user_id)
);
create index on public.care_circle_members (user_id);

-- ---------------------------------------------------------------- recipients (the parent)
create table public.care_recipients (
  id                  uuid primary key default gen_random_uuid(),
  circle_id           uuid not null references public.care_circles (id) on delete cascade,
  name                text not null,
  dob                 date,
  whatsapp_phone      text check (whatsapp_phone ~ '^\+[1-9][0-9]{7,14}$'),  -- E.164
  timezone            text not null default 'Asia/Kolkata',
  language            text not null default 'en' check (language in ('en', 'hi')),
  conditions          text[] not null default '{}',
  allergies           text[] not null default '{}',
  blood_group         text check (blood_group in ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  doctor_contacts     jsonb not null default '[]',
  emergency_contacts  jsonb not null default '[]',
  -- Set ONLY by the backend when the parent opts in on WhatsApp; never by a caregiver.
  consent_at          timestamptz,
  opted_out_at        timestamptz,   -- parent replied STOP
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
create index on public.care_recipients (circle_id);
create trigger care_recipients_updated_at before update on public.care_recipients
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------- medicines
create table public.medicines (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  name          text not null,
  dosage        text,
  photo_url     text,                 -- storage path in private bucket, not a public URL
  stock_count   integer check (stock_count >= 0),
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index on public.medicines (recipient_id);
create trigger medicines_updated_at before update on public.medicines
  for each row execute function public.set_updated_at();

create table public.medicine_schedules (
  id            uuid primary key default gen_random_uuid(),
  medicine_id   uuid not null references public.medicines (id) on delete cascade,
  time_of_day   time not null,        -- parent-local wall-clock time
  days_of_week  smallint[] not null default '{0,1,2,3,4,5,6}'   -- 0 = Sunday
    check (cardinality(days_of_week) > 0 and days_of_week <@ array[0,1,2,3,4,5,6]::smallint[]),
  start_date    date not null default current_date,
  end_date      date,
  created_at    timestamptz not null default now(),
  check (end_date is null or end_date >= start_date)
);
create index on public.medicine_schedules (medicine_id);

-- Written by the backend scheduler / WhatsApp webhook only.
create table public.dose_events (
  id                    uuid primary key default gen_random_uuid(),
  schedule_id           uuid not null references public.medicine_schedules (id) on delete cascade,
  scheduled_at          timestamptz not null,
  status                public.dose_status not null default 'pending',
  sent_at               timestamptz,
  responded_at          timestamptz,
  whatsapp_message_id   text,
  unique (schedule_id, scheduled_at)   -- scheduler can safely re-run
);
create index on public.dose_events (status, scheduled_at);
create unique index on public.dose_events (whatsapp_message_id) where whatsapp_message_id is not null;

-- ---------------------------------------------------------------- check-ins
create table public.checkins (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  date          date not null,        -- parent-local date
  sent_at       timestamptz,
  responded_at  timestamptz,
  status        public.checkin_status not null default 'pending',
  unique (recipient_id, date)
);

-- ---------------------------------------------------------------- reports
create table public.reports (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  uploaded_by   uuid references public.profiles (id) on delete set null,
  file_url      text not null,        -- storage path in private bucket
  report_date   date,
  type          text,
  ai_summary    text,
  ai_status     public.report_ai_status not null default 'pending',
  created_at    timestamptz not null default now()
);
create index on public.reports (recipient_id, report_date desc);

create table public.report_values (
  id               uuid primary key default gen_random_uuid(),
  report_id        uuid not null references public.reports (id) on delete cascade,
  test_name        text not null,     -- BP is stored as two rows: systolic / diastolic
  value            numeric not null,
  unit             text,
  reference_range  text,
  flag             public.value_flag,
  user_verified    boolean not null default false   -- caregiver reviewed/corrected the AI value
);
create index on public.report_values (report_id);
create index on public.report_values (test_name);

-- ---------------------------------------------------------------- appointments
create table public.appointments (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  doctor_name   text not null,
  datetime      timestamptz not null,
  notes         text,
  created_by    uuid references public.profiles (id) on delete set null
);
create index on public.appointments (recipient_id, datetime);

-- ---------------------------------------------------------------- emergency links
create table public.emergency_links (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  token         text not null unique default encode(extensions.gen_random_bytes(32), 'hex'),
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create index on public.emergency_links (recipient_id);

-- ---------------------------------------------------------------- notifications
create table public.notifications (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.profiles (id) on delete cascade,
  type        text not null,
  payload     jsonb not null default '{}',   -- never put raw health data / phone numbers here
  read_at     timestamptz,
  created_at  timestamptz not null default now()
);
create index on public.notifications (user_id, created_at desc);

-- ---------------------------------------------------------------- webhook idempotency
-- Meta can resend webhooks; the backend inserts the message id first and skips on conflict.
create table public.whatsapp_events (
  id           text primary key,     -- Meta message / status id
  received_at  timestamptz not null default now()
);
