-- Low-stock ("refill") alerts.
--
-- 1. Taking a dose uses up stock: the backend calls consume_stock() when a parent confirms a dose.
-- 2. A database trigger tells the whole family the moment stock falls to the refill level, however it got
--    there (a dose taken, the caregiver editing the number, or a medicine added that is already low).
--    It fires once per drop: it stays quiet while stock remains low, and fires again only after a refill.

create function public.consume_stock(p_medicine uuid, p_units integer default 1) returns void
language sql security definer set search_path = '' as $$
  update public.medicines
  set stock_count = greatest(stock_count - greatest(p_units, 0), 0)
  where id = p_medicine and stock_count is not null;
$$;
revoke execute on function public.consume_stock(uuid, integer) from public, anon, authenticated;
grant execute on function public.consume_stock(uuid, integer) to service_role;

create function public.notify_low_stock() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_was_low boolean := false;
begin
  if not (new.active and new.stock_count is not null and new.refill_threshold is not null
          and new.stock_count <= new.refill_threshold) then
    return new;
  end if;

  if tg_op = 'UPDATE' then
    v_was_low := old.active and old.stock_count is not null and old.refill_threshold is not null
                 and old.stock_count <= old.refill_threshold;
  end if;
  if v_was_low then
    return new;   -- already low: do not repeat the alert
  end if;

  insert into public.notifications (user_id, type, payload)
  select m.user_id, 'refill_needed', jsonb_build_object('recipient_id', new.recipient_id, 'medicine_id', new.id)
  from public.care_recipients r
  join public.care_circle_members m on m.circle_id = r.circle_id
  where r.id = new.recipient_id;
  return new;
end $$;

create trigger medicines_notify_low_stock
  after insert or update of stock_count, refill_threshold, active on public.medicines
  for each row execute function public.notify_low_stock();
