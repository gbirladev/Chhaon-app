-- A new reminder starts on the PARENT's local date, not the server's date (migration 0022 had slipped back to current_date).
-- For a parent behind UTC (e.g. New York in the evening) the server date is already tomorrow, so today's late doses were skipped.
create or replace function public.create_medicine(
  p_recipient uuid, p_name text, p_dosage text, p_instructions text, p_photo_url text,
  p_stock integer, p_refill_threshold integer, p_times time[],
  p_start date default null, p_end date default null
) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_medicine uuid;
  v_start date;
begin
  perform public.check_medicine_input(p_recipient, p_name, p_dosage, p_photo_url, p_times);

  select coalesce(p_start, (now() at time zone r.timezone)::date) into v_start
    from public.care_recipients r where r.id = p_recipient;
  if v_start is null then
    raise exception 'parent not found' using errcode = '42501';
  end if;

  insert into public.medicines (recipient_id, name, dosage, instructions, photo_url, stock_count, refill_threshold)
  values (p_recipient, btrim(p_name), p_dosage, p_instructions, p_photo_url, p_stock, p_refill_threshold)
  returning id into v_medicine;

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date, end_date)
  select v_medicine, t, v_start, p_end from (select distinct unnest(p_times) as t) x;

  return v_medicine;
end $$;

create or replace function public.update_medicine(
  p_medicine uuid, p_name text, p_dosage text, p_instructions text, p_photo_url text,
  p_refill_threshold integer, p_times time[]
) returns void
language plpgsql security invoker set search_path = '' as $$
declare
  v_recipient uuid;
  v_today date;
begin
  select recipient_id into v_recipient from public.medicines where id = p_medicine;
  if v_recipient is null then
    raise exception 'medicine not found' using errcode = '42501';
  end if;
  perform public.check_medicine_input(v_recipient, p_name, p_dosage, p_photo_url, p_times);
  select (now() at time zone r.timezone)::date into v_today from public.care_recipients r where r.id = v_recipient;

  update public.medicines
     set name = btrim(p_name), dosage = p_dosage, instructions = p_instructions,
         photo_url = coalesce(p_photo_url, photo_url), refill_threshold = p_refill_threshold
   where id = p_medicine;
  if not found then
    raise exception 'medicine not found' using errcode = '42501';
  end if;

  delete from public.medicine_schedules where medicine_id = p_medicine and not (time_of_day = any (p_times));

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date)
  select p_medicine, t, v_today
    from (select distinct unnest(p_times) as t) x
   where not exists (select 1 from public.medicine_schedules s where s.medicine_id = p_medicine and s.time_of_day = x.t);
end $$;

revoke execute on function public.create_medicine(uuid, text, text, text, text, integer, integer, time[], date, date) from public, anon;
grant execute on function public.create_medicine(uuid, text, text, text, text, integer, integer, time[], date, date) to authenticated;
revoke execute on function public.update_medicine(uuid, text, text, text, text, integer, time[]) from public, anon;
grant execute on function public.update_medicine(uuid, text, text, text, text, integer, time[]) to authenticated;
