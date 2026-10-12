-- Edit a medicine and its reminder times in one step. Runs as the caller, so row level security decides who may edit.
-- Times that stay keep their schedule row (and its dose history); removed times are deleted; new times are added.
create function public.update_medicine(
  p_medicine uuid,
  p_name text,
  p_dosage text,
  p_instructions text,
  p_photo_url text,          -- null keeps the current photo
  p_refill_threshold integer, -- null turns refill alerts off
  p_times time[]
) returns void
language plpgsql security invoker set search_path = '' as $$
begin
  if coalesce(cardinality(p_times), 0) = 0 then
    raise exception 'at least one reminder time is required' using errcode = '22023';
  end if;

  update public.medicines
     set name = p_name,
         dosage = p_dosage,
         instructions = p_instructions,
         photo_url = coalesce(p_photo_url, photo_url),
         refill_threshold = p_refill_threshold
   where id = p_medicine;
  if not found then
    raise exception 'medicine not found' using errcode = '42501';
  end if;

  delete from public.medicine_schedules where medicine_id = p_medicine and not (time_of_day = any (p_times));

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date)
  select p_medicine, t, current_date
    from unnest(p_times) as t
   where not exists (select 1 from public.medicine_schedules s where s.medicine_id = p_medicine and s.time_of_day = t);
end $$;

revoke execute on function public.update_medicine(uuid, text, text, text, text, integer, time[]) from public, anon;
grant execute on function public.update_medicine(uuid, text, text, text, text, integer, time[]) to authenticated;
