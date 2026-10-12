-- When an appointment was booked: the reminder rules need it (booked after 6 pm the evening before -> morning reminder only).
alter table public.appointments add column created_at timestamptz not null default now();
