-- How a tablet LOOKS (colour + shape), so a parent or a helper who cannot read the strip can still pick the right one
-- ("neeli gol goli"). Suggested by a doctor in the pilot, 2026-11-05. Both are optional; the reminder then says, for
-- example, "Metformin (white round tablet)".
-- Fixed lists so the WhatsApp text can be written properly in each language (Hindi colour words change with the noun).
-- Saved by the Java backend (POST/PUT /api/v1/.../medicines, which checks the same lists); the create_medicine /
-- update_medicine SQL functions and their permissions stay unchanged.
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that maps these columns:
-- the backend validates its tables at start-up and would refuse to start without them. Safe to run twice.

alter table public.medicines add column if not exists pill_colour text;
alter table public.medicines add column if not exists pill_shape text;

alter table public.medicines drop constraint if exists medicines_pill_colour_known;
alter table public.medicines add constraint medicines_pill_colour_known check (
  pill_colour is null or pill_colour in ('white', 'yellow', 'orange', 'pink', 'red', 'blue', 'green', 'brown', 'purple')
);

alter table public.medicines drop constraint if exists medicines_pill_shape_known;
alter table public.medicines add constraint medicines_pill_shape_known check (
  pill_shape is null or pill_shape in ('round', 'oval', 'long', 'capsule')
);

comment on column public.medicines.pill_colour is 'What colour the tablet is (fixed list), shown to the family and named in the WhatsApp reminder';
comment on column public.medicines.pill_shape is 'round | oval | long | capsule, named in the WhatsApp reminder';
