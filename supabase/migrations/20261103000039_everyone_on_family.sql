-- Everyone is on the Family plan for now: Chhaon is free while it grows, so nobody should hit a Free limit
-- (Free allowed only 2 medicines per parent and no invites once the 30-day trial ended).
--
-- Nothing is removed. The plans, their limits, the trial and the Plans screen are all still there; the app hides the
-- Plans screen while VITE_PLANS_ENABLED is not "true". Family = 4 parents, 10 people per family, 20 medicines per
-- parent, 100 reports per parent.
--
-- To go back (see docs/plans.md, "Plans off"): set the default back to 'free' and move the accounts you want back.

alter table public.profiles alter column plan set default 'family';

-- Existing accounts on Free or Plus move up to Family. Ultra (the owner's own account) stays Ultra.
-- (This runs as the database owner: the guard that stops a caregiver changing their own plan does not apply.)
update public.profiles set plan = 'family' where plan in ('free', 'plus');

-- Admin accounts are always unlimited (Ultra): the people running Chhaon must never hit a limit while testing.
-- The existing admins move now; the trigger keeps it true when someone is marked admin later, and keeps a hand edit
-- from putting an admin on a smaller plan by mistake (remove the admin flag first if that is really wanted).
update public.profiles set plan = 'ultra' where is_admin;

create or replace function public.admin_is_ultra() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.plan := 'ultra';
  return new;
end $$;
revoke all on function public.admin_is_ultra() from public, anon, authenticated;

-- Named so that it runs after profiles_guard_plan (triggers fire in name order): the guard still sees what the caller sent.
drop trigger if exists profiles_set_admin_ultra on public.profiles;
create trigger profiles_set_admin_ultra before insert or update of is_admin, plan on public.profiles
  for each row when (new.is_admin) execute function public.admin_is_ultra();
