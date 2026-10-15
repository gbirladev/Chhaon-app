# One reminder per time slot (migration 0030)

All medicines a parent has due at the **same moment** go in one WhatsApp message: "Time for your medicines: Metformin (500 mg), Amlodipine (5 mg), ...". One **Taken all** button confirms every one.
A parent with 5 medicines at 2 times a day gets about 2 reminders a day instead of 10.

## Switch
`SLOT_REMINDERS_ENABLED` (server setting, default off). While off, every dose is reminded on its own, exactly as before.
A slot with a single medicine always keeps its own reminder (with the medicine photo).

## Turning it on, in this order
1. `npx supabase@2.120.0 db push < /dev/null`: migration 0030 (the WhatsApp message id stops being unique per dose; adds `confirm_dose_slot`). **Do this before turning the switch on**, otherwise saving the same message id on a second dose fails.
2. Deploy the API (push).
3. Meta must have **approved `carelink_dose_reminder_multi`** in English and Hindi (see `docs/whatsapp-templates.md`, section 15).
4. Set `SLOT_REMINDERS_ENABLED=true` in Render. Check one real slot end to end on your own number before leaving it on.

## How it works
- The tick groups due doses by parent and exact time. All doses of a slot are claimed together (pending to sent) before anything is sent and handed back if the send fails, so nothing is sent twice.
- Each dose of the slot stores the same `whatsapp_message_id` (a failed delivery tells the family once, not once per dose).
- **Taken all** carries `dose:<id>:all`. The backend checks the tap came from the parent's number, then `confirm_dose_slot` confirms every sent or missed dose of that parent at that moment in one step (a late tap still counts). Stock goes down once per medicine newly confirmed; a repeated tap changes nothing. The old **Taken** button on older messages still confirms just its own dose.
- If a whole slot goes unanswered for the missed window, the family gets **one** alert ("5 medicines due at 8:00 AM") using the existing alert template, instead of one per medicine. This grouping applies whether the switch is on or off.

## Limits
- Slots must match exactly (08:00 and 08:05 are two slots). The Morning, Afternoon and Night presets share one time each, so they group naturally.
- A multi-medicine message has no photo (WhatsApp allows one image per message).
- At most 6 medicines are listed; more end with "and N more" (Hindi: "और N दवाएँ").

Tests: `api/tests/test_slot_reminders.py` (13) and `supabase/tests/slot_reminders.test.sql` (7).
