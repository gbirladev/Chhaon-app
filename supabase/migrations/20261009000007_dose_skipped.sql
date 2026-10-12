-- A dose that was never sent (parent has not agreed, opted out, or WhatsApp kept failing) is 'skipped',
-- not 'missed': 'missed' means we asked and the parent did not answer.
alter type public.dose_status add value if not exists 'skipped';
