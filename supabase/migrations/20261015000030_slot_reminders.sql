-- One reminder per time slot: a single WhatsApp message can now cover every medicine a parent has due at the same moment.

-- 1. The WhatsApp message id used to be unique per dose. Several doses now share the message that covered them.
drop index if exists public.dose_events_whatsapp_message_id_idx;
create index dose_events_whatsapp_message_id_idx on public.dose_events (whatsapp_message_id) where whatsapp_message_id is not null;

-- 2. "Taken all": confirm every dose of the same parent that was due at the same moment as the one tapped, in one atomic step.
-- Only doses we actually sent (or that were already marked missed: a late tap still counts) change; it returns what was newly
-- confirmed so the backend can use up stock once per medicine. The backend checks that the tap came from the parent's own number.
create function public.confirm_dose_slot(p_dose_event uuid) returns table (medicine_id uuid, dosage text)
language sql security definer set search_path = '' as $$
  with anchor as (
    select m.recipient_id, de.scheduled_at
      from public.dose_events de
      join public.medicine_schedules s on s.id = de.schedule_id
      join public.medicines m on m.id = s.medicine_id
     where de.id = p_dose_event
  )
  update public.dose_events de
     set status = 'taken', responded_at = now()
    from public.medicine_schedules s, public.medicines m, anchor a
   where de.schedule_id = s.id and s.medicine_id = m.id
     and m.recipient_id = a.recipient_id and de.scheduled_at = a.scheduled_at
     and de.status in ('sent', 'missed')
  returning m.id, m.dosage
$$;
revoke execute on function public.confirm_dose_slot(uuid) from public, anon, authenticated;
grant execute on function public.confirm_dose_slot(uuid) to service_role;
