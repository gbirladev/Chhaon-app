-- A dose confirmed after it was already marked missed is "taken_late" (kept apart from "taken" so the family and the stats can tell).
-- This is its own migration because a new enum value cannot be used in the transaction that adds it.
alter type public.dose_status add value if not exists 'taken_late';
