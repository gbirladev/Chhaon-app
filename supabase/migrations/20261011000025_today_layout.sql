-- Each caregiver picks how "Today's medicines" is drawn. Stored on the profile so it follows them to any phone.
alter table public.profiles
  add column today_layout text check (today_layout is null or today_layout in ('route','curvy','timeline','arc','focus','ring','pillbox','ladder','clock','chat'));
