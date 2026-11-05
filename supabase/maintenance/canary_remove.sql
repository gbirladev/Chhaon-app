-- Canary family: delete it completely (caregiver "Chhaon Canary", person "Test Papa", medicine, times, every dose).
-- Plain-English steps: docs/canary.md. Paste into the SQL Editor and press Run. Safe to run twice.
-- Afterwards the Monitor's Canary card shows "not set up" (grey, no alert). Also remove the "Canary" session in the
-- gateway dashboard if the spare SIM is no longer used.

select public.canary_remove() as canary_people_removed;

-- Should show 0 and 0:
select (select count(*) from public.care_recipients where is_canary) as canary_people,
       (select count(*) from public.profiles where is_canary) as canary_caregivers;
