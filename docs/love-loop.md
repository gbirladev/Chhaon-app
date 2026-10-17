# Love loop (caregiver reactions sent to the parent)

A caregiver taps a heart (or a preset, or writes up to 150 characters) on a taken dose or an answered check-in. The parent gets one
warm WhatsApp message, for example "Riya saw that you took your medicine ❤️". It is a free-form session message sent inside the
open 24-hour window: **no template**.

## Switches (everything is off by default)
- Server: `LOVE_LOOP_ENABLED=true` (Render). `GET /features` returns `love`.
- Per parent: `love_settings.enabled` (the family switches it with `set_love_enabled()`), and the parent must have agreed and not said STOP or NO HEARTS.
- Pilot: `love_settings.pilot_group` = `love` or `control` (set by hand in the SQL editor). A control family can never turn it on.

## Rules (services/love.py)
1. Off / opted out / control group: queued reactions become `skipped` and the sender is told.
2. 48 hours in the queue: `expired`, and the sender is told once.
3. The 2-minute batching wait (`due_at`).
4. Window closed (the parent has not written for 24 h): stays queued, sent right after the parent's next reply.
5. Quiet hours (default 21:00 to 07:00, parent time): held until morning.
6. Daily cap (default 3 messages per parent-day): held until tomorrow.
7. Everything queued for a parent goes out as ONE message ("Riya and Rahul sent you ❤️").
A batch is claimed with a guarded update before sending, so overlapping ticks and webhook retries never send twice. A failed send is given back and retried until the 48-hour expiry.

## The parent
- Writes `NO HEARTS` / `stop hearts` / `दिल बंद`: only the love messages stop. Queued reactions are skipped, the family is told, the parent gets one polite reply. `HEARTS` / `दिल चालू` brings them back. This is read **before** STOP so "stop hearts" can never opt a parent out of everything. Plain STOP is unchanged.
- Any text, emoji or tap is stored in `parent_messages` (only for families that switched the love loop on; voice notes and photos are only noted, never kept). A text or emoji within 60 minutes of a love message is linked to it as the reply.
- The family gets at most one in-app notice per parent per 30 minutes ("parent_message").

## Statuses
`delivered` and `read` webhooks move a reaction forward only (a late or replayed event never moves it back). `failed` marks it failed and tells the sender.

## Copy
Everything the parent reads is in `api/app/love_copy.py` (English and Hindi). Tone rules: warm, respectful, no praise ("good job"), no exclamation marks. A test enforces the banned words.

## Cost tracking
Each reaction records `in_window` and `category` (`service`). Logs carry counts only, never text or phone numbers.

## Pilot numbers
`select public.love_stats(14)` (admin account): reactions by status, parent replies to reactions, and the confirmation rate before vs after for love and control families.

## Screens (step 3, built 2026-10-17)
- Family tab > **Love messages** (shown only when `/features` says `love`): switch per parent, "what {parent} calls you" (saved per family circle), the rules in plain words, the parent's latest messages.
- Today: a **love card** (only when switched on and the parent has agreed). Each taken dose (a time slot of doses is one line) and the answered check-in has hearts (❤️ 🙏 🤗) and **Write** (3 presets or up to 150 characters). Under it: what was sent, the status (waiting / sent / delivered / read / not sent, with a plain reason), and what the parent wrote back. After NO HEARTS the buttons are replaced by a note.
- Notifications: not delivered (expired / parent said no hearts / WhatsApp failed), parent opted out or back in, parent wrote to you.
- Pilot stats page (admin): reactions by status, replies, and confirmation before vs after for love and control families (`love_stats()`).
- English and Hindi. Browser-tested (`love-ui.mjs`, 24 checks). The Hindi needs native review.

## Status of the build
- Step 1 database, step 2 backend, step 3 screens: done. NOT pushed.
- Not built: the optional WhatsApp alert for parent messages (needs a new template).
- To use it: `db push` (migrations 0033 and later), deploy the API, set `LOVE_LOOP_ENABLED=true` in Render, the family switches it on per parent. Pilot comparison: set `love_settings.pilot_group = 'control'` by hand for the families without it.
