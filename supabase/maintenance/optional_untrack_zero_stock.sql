-- OPTIONAL, ONE-OFF, NOT RUN BY ANYONE YET (round 2, R3, branch r3-facts). Owner's decision.
--
-- Until this round the "Add medicine" form required a stock number, and the web app showed a medicine without one as 0, so some
-- medicines may hold stock_count = 0 that nobody really counted. They show a red "Out of stock" and may have raised "has run out"
-- alerts. From now on an empty stock is NULL = "not tracked" (no stock warnings, no refill or out-of-stock alerts), and a family can
-- also tap "Stop counting stock" on the medicine screen. This script turns the likely-uncounted zeros into "not tracked".
--
-- Which ones: stock exactly 0, never changed since the medicine was created (updated_at within a minute of created_at), and no dose
-- of it was ever confirmed (a confirmed dose would have used stock, so a 0 after that may be real). Anything else is left alone.
--
-- Sydney SQL Editor: run STEP 1 first and look at the rows (it shows only the LAST result of several statements, so run one at a time).
-- Then run STEP 2 inside the same transaction only if the list looks right. It changes no other column.

-- STEP 1: preview (names only, no phone numbers)
select m.id, m.name, m.stock_count, m.refill_threshold, m.created_at, m.updated_at
from public.medicines m
where m.stock_count = 0
  and m.updated_at <= m.created_at + interval '1 minute'
  and not exists (select 1 from public.medicine_schedules s join public.dose_events d on d.schedule_id = s.id
                  where s.medicine_id = m.id and d.status = 'taken')
order by m.created_at;

-- STEP 2: apply (same filter). The low-stock trigger does nothing for NULL, so no alert is created.
-- begin;
-- update public.medicines m set stock_count = null
-- where m.stock_count = 0
--   and m.updated_at <= m.created_at + interval '1 minute'
--   and not exists (select 1 from public.medicine_schedules s join public.dose_events d on d.schedule_id = s.id
--                   where s.medicine_id = m.id and d.status = 'taken');
-- commit;
