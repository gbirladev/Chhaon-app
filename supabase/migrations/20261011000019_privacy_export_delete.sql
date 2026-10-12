-- Privacy (DPDP): caregiver consent record + account erasure helpers.

-- 1. Caregiver agreement to the privacy policy. NULL = not yet asked/agreed (existing users see a one-time sheet).
alter table public.profiles
  add column privacy_accepted_at timestamptz,
  add column policy_version      text;
-- (profiles_update already lets a caregiver change only their own row; this is their own statement of consent.)

-- 2. Parents whose records disappear when this caregiver leaves: circles where they are the ONLY member.
--    The API deletes the stored files of these parents first, then calls erase_caregiver().
create function public.sole_member_recipients(p_user uuid) returns table (recipient_id uuid)
language sql stable security definer set search_path = '' as $$
  select r.id
    from public.care_recipients r
   where r.circle_id in (
     select m.circle_id from public.care_circle_members m
      where m.user_id = p_user
        and not exists (select 1 from public.care_circle_members o where o.circle_id = m.circle_id and o.user_id <> p_user)
   );
$$;

-- 3. Removes a caregiver from every circle (service role only, called just before the auth user is deleted).
--    * circle with nobody else: the circle is deleted (cascades to parents, medicines, schedules, doses, check-ins,
--      reports, values, appointments, emergency links)
--    * circle with others: the caregiver just leaves; if they were the only owner, the longest-standing member becomes owner
--    * their WhatsApp alert number is cleared
--    Safe to run again (a second run finds nothing to do).
create function public.erase_caregiver(p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  c record;
  heir uuid;
begin
  for c in
    select m.circle_id, m.role from public.care_circle_members m where m.user_id = p_user order by m.circle_id
  loop
    perform 1 from public.care_circles where id = c.circle_id for update;   -- serialise with a concurrent leave/promotion
    if not exists (select 1 from public.care_circle_members o where o.circle_id = c.circle_id and o.user_id <> p_user) then
      delete from public.care_circles where id = c.circle_id;
    else
      if c.role = 'owner'
         and not exists (select 1 from public.care_circle_members o where o.circle_id = c.circle_id and o.user_id <> p_user and o.role = 'owner') then
        select o.user_id into heir from public.care_circle_members o
         where o.circle_id = c.circle_id and o.user_id <> p_user
         order by o.created_at, o.user_id limit 1;
        update public.care_circle_members set role = 'owner' where circle_id = c.circle_id and user_id = heir;
      end if;
      delete from public.care_circle_members where circle_id = c.circle_id and user_id = p_user;
    end if;
  end loop;
  -- circles they created that have no members at all (left behind by older flows)
  delete from public.care_circles k where k.created_by = p_user
    and not exists (select 1 from public.care_circle_members o where o.circle_id = k.id);
  update public.profiles
     set alert_phone = null, alert_requested_at = null, alert_confirmed_at = null, alert_consent_at = null
   where id = p_user;
  delete from public.notifications where user_id = p_user;
end $$;

revoke execute on function public.sole_member_recipients(uuid), public.erase_caregiver(uuid) from public, anon, authenticated;
grant execute on function public.sole_member_recipients(uuid), public.erase_caregiver(uuid) to service_role;
