-- The "Simple list" Today style (R7, 2026-11-05) can be saved to the caregiver's profile like the 10 older styles,
-- so the choice follows them to another phone. Without this the app keeps the choice on that phone only.
-- Sydney: paste into the SQL Editor (no migration history there). Safe to run twice. Order vs other migrations: any.

alter table public.profiles drop constraint if exists profiles_today_layout_check;
alter table public.profiles add constraint profiles_today_layout_check check (
  today_layout is null or today_layout in ('route','curvy','timeline','arc','focus','ring','pillbox','ladder','clock','chat','simple')
);
