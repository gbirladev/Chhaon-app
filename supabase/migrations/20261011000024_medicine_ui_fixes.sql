-- Low-stock alerts fire only when the STOCK crosses the level, not when only the refill level or the paused switch changes
-- (toggling the refill reminder off/on, or pause/resume, on an already-low medicine must not alert again).
-- "was low" is judged with the NEW threshold/active flag against the OLD stock.
create or replace function public.notify_low_stock() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_low     boolean := new.active and new.stock_count is not null and new.refill_threshold is not null
                       and new.stock_count <= new.refill_threshold;
  v_was_low boolean := false;
  v_type    text;
begin
  if not v_low then
    return new;
  end if;

  if tg_op = 'UPDATE' then
    v_was_low := old.stock_count is not null and old.stock_count <= new.refill_threshold;
  end if;

  if new.stock_count = 0 and (tg_op = 'INSERT' or old.stock_count is distinct from 0) then
    v_type := 'out_of_stock';
  elsif not v_was_low then
    v_type := 'refill_needed';
  else
    return new;   -- already low and not newly empty: do not repeat
  end if;

  insert into public.notifications (user_id, type, payload)
  select m.user_id, v_type, jsonb_build_object('recipient_id', new.recipient_id, 'medicine_id', new.id)
  from public.care_recipients r
  join public.care_circle_members m on m.circle_id = r.circle_id
  where r.id = new.recipient_id;
  return new;
end $$;
