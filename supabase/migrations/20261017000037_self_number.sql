-- "Me": your own profile must not share its WhatsApp number with another profile of yours (and the other way round).
-- Two PARENTS may still share one phone (a couple with one phone), but your own number on a parent's profile, or a parent's number on your own
-- profile, is a typing mistake: reminders and confirmations would go to the wrong person.
create or replace function public.guard_self_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_owner uuid;
begin
  select created_by into v_owner from public.care_circles where id = new.circle_id;
  if tg_op = 'UPDATE' then
    if new.is_self is distinct from old.is_self or (new.is_self and new.circle_id is distinct from old.circle_id) then
      raise exception 'an own profile cannot be changed into a parent profile' using errcode = '42501';
    end if;
  elsif not new.is_self then
    if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id and r.is_self) then
      raise exception 'self_circle_private' using errcode = 'P0001', hint = 'self_private';
    end if;
  else
    if exists (select 1 from public.care_recipients r join public.care_circles c on c.id = r.circle_id where r.is_self and c.created_by = v_owner) then
      raise exception 'self_exists' using errcode = 'P0001', hint = 'self_exists';
    end if;
    if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id)
       or exists (select 1 from public.care_circle_members m where m.circle_id = new.circle_id and m.user_id is distinct from v_owner) then
      raise exception 'self_circle_not_private' using errcode = 'P0001', hint = 'self_private';
    end if;
  end if;

  if new.whatsapp_phone is not null and (tg_op = 'INSERT' or new.whatsapp_phone is distinct from old.whatsapp_phone)
     and exists (
       select 1 from public.care_recipients r join public.care_circles c on c.id = r.circle_id
        where c.created_by = v_owner and r.id <> new.id and r.whatsapp_phone = new.whatsapp_phone and (r.is_self or new.is_self)) then
    raise exception 'self_number_in_use' using errcode = 'P0001', hint = 'self_number';
  end if;
  return new;
end $$;
