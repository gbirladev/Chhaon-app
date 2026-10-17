"""Love loop: a caregiver's small warm reaction reaches the parent on WhatsApp. (docs/love-loop.md)

Reactions are FREE-FORM session messages sent inside the open 24-hour window (the parent just replied, so it is usually open and
free). No template. The window is open when the parent wrote to us (any tap or message) less than 24 hours ago.

Rules, applied in this order by the scheduler step `run` (and right after a parent's reply by `flush_recipient`):
    off        the server switch AND the parent's own setting must be on, the parent must have agreed and not said STOP / NO HEARTS;
               otherwise queued reactions are marked skipped
    expire     48 hours in the queue -> expired, and the sender is told it was not delivered
    wait       the 2-minute batching wait (reactions.due_at)
    window     closed -> stays queued until the parent's next reply
    quiet      the parent's quiet hours (default 9 PM to 7 AM, parent time) -> held until morning
    cap        at most `daily_cap` love messages per parent per parent-day (default 3) -> held until tomorrow
    batch      everything queued for the parent goes out as ONE message ("Riya and Rahul sent you ❤️")

Safety rules, same as the rest of the backend: a batch is CLAIMED with a guarded UPDATE (`... where batch_id is null`) before anything
is sent, so overlapping ticks, a webhook right after a tick, or Meta's retries never send it twice. Messages and logs never contain the
text of a reaction or a phone number (only counts).
"""

import logging
import re
import unicodedata
import uuid
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from supabase import Client

from app.config import Settings
from app.love_copy import HEART_WORDS, OFF_WORDS, ON_WORDS, copy
from app.services import messaging
from app.services.notify import notify_circle
from app.whatsapp.client import Messenger, WhatsAppError

log = logging.getLogger("carelink.love")

WINDOW = timedelta(hours=24)
WINDOW_MARGIN = timedelta(minutes=5)         # never gamble on the last minutes of the window
PARENT_MESSAGE_NOTICE_GAP = timedelta(minutes=30)   # the family hears about parent messages in batches, not one per emoji
MAX_BODY = 500
MAX_HEART_COMMAND_WORDS = 4
CONTROL = re.compile(r"[\x00-\x1f\x7f  ]+")


def _iso(value: datetime) -> str:
    return value.isoformat()


def _at(value: str) -> datetime:
    return datetime.fromisoformat(value).astimezone(UTC)


# ---------------------------------------------------------------- who and when
def _first(query) -> dict | None:
    """One row or None. (maybe_single() itself returns None, not an empty response, when nothing matches.)"""
    rows = query.limit(1).execute().data
    return rows[0] if rows else None


def _settings_row(db: Client, recipient_id: str) -> dict | None:
    return _first(db.table("love_settings").select("*").eq("recipient_id", recipient_id))


def love_active(db: Client, settings: Settings, recipient: dict, row: dict | None = None) -> dict | None:
    """The parent's love settings if reactions may be sent to this parent right now, else None."""
    if not settings.love_loop_enabled or recipient.get("is_self"):
        return None
    row = row or _settings_row(db, recipient["id"])
    if not row or not row["enabled"] or row["parent_opted_out_at"] or row["pilot_group"] == "control":
        return None
    return row if messaging.allowed(db, recipient) else None


def window_open(db: Client, recipient_id: str, now: datetime) -> bool:
    last = (
        db.table("parent_messages").select("created_at").eq("recipient_id", recipient_id)
        .order("created_at", desc=True).limit(1).execute().data
    )
    return bool(last) and now - _at(last[0]["created_at"]) < WINDOW - WINDOW_MARGIN


def _zone(tz_name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(tz_name or "Asia/Kolkata")
    except ZoneInfoNotFoundError:
        return ZoneInfo("Asia/Kolkata")


def _parse_time(value: str) -> time:
    return time.fromisoformat(value)


def in_quiet_hours(row: dict, tz_name: str | None, now: datetime) -> bool:
    start, end = _parse_time(row["quiet_start"]), _parse_time(row["quiet_end"])
    local = now.astimezone(_zone(tz_name)).time()
    if start == end:
        return False
    return start <= local < end if start < end else (local >= start or local < end)   # 21:00-07:00 wraps midnight


def sent_today(db: Client, recipient: dict, now: datetime) -> int:
    """Love MESSAGES (a batch is one) already sent in the parent's current day."""
    zone = _zone(recipient.get("timezone"))
    midnight = now.astimezone(zone).replace(hour=0, minute=0, second=0, microsecond=0).astimezone(UTC)
    rows = db.table("reactions").select("batch_id").eq("recipient_id", recipient["id"]).gte("sent_at", _iso(midnight)).execute().data
    return len({r["batch_id"] for r in rows if r["batch_id"]})


# ---------------------------------------------------------------- the message
def _first_name(value: str | None) -> str:
    return (CONTROL.sub(" ", value or "").strip().split(" ") or [""])[0][:30]


def _names(db: Client, circle_id: str, sender_ids: list[str]) -> dict[str, str]:
    """What the parent calls each sender: the name the sender set for this parent, else their first name."""
    members = db.table("care_circle_members").select("user_id, parent_calls_me").eq("circle_id", circle_id).in_("user_id", sender_ids).execute().data
    called = {m["user_id"]: (m["parent_calls_me"] or "").strip() for m in members}
    profiles = db.table("profiles").select("id, name").in_("id", sender_ids).execute().data
    own = {p["id"]: _first_name(p["name"]) for p in profiles}
    return {sid: called.get(sid) or own.get(sid) or "" for sid in sender_ids}


def _join(names: list[str], c: dict) -> str:
    if len(names) <= 1:
        return "".join(names)
    return c["comma"].join(names[:-1]) + c["and"] + names[-1]


def _note(reaction: dict, c: dict) -> str | None:
    if reaction["type"] == "preset":
        return c["presets"].get(reaction["text"])
    return reaction["text"] if reaction["type"] == "custom" or reaction["text"] else None


def build_message(reactions: list[dict], names: dict[str, str], language: str | None) -> str:
    """One message for one or several reactions. `names` maps sender id -> what the parent calls them."""
    c = copy(language)
    label = lambda r: names.get(r["sender_id"]) or c["fallback_name"]
    if len(reactions) == 1:
        r = reactions[0]
        lines = []
        if r["type"] == "emoji":
            lines.append(c["dose" if r["source_type"] == "dose" else "checkin"].format(who=label(r), emoji=r["emoji"]))
            if r["text"]:
                lines.append(c["says"].format(who=label(r), note=r["text"]))
        else:
            lines.append(c["says"].format(who=label(r), note=_note(r, c)))
        return "\n".join(lines)
    # several: the hearts together on one line, then each person's own words
    who: list[str] = []
    emojis: list[str] = []
    for r in reactions:
        if r["type"] == "emoji":
            if label(r) not in who:
                who.append(label(r))
            if r["emoji"] not in emojis:
                emojis.append(r["emoji"])
    lines = [c["emoji_many"].format(who=_join(who, c), emoji="".join(emojis))] if emojis else []
    for r in reactions:
        note = _note(r, c)
        if note:
            lines.append(c["says"].format(who=label(r), note=note))
    return "\n".join(lines)


# ---------------------------------------------------------------- notices to the sender
def _tell_sender(db: Client, reaction: dict, type_: str, reason: str) -> None:
    if reaction.get("sender_id"):
        db.table("notifications").insert({
            "user_id": reaction["sender_id"], "type": type_,
            "payload": {"recipient_id": reaction["recipient_id"], "reaction_id": reaction["id"], "reason": reason},
        }).execute()


def _close(db: Client, reactions: list[dict], status: str, reason: str, notify: str | None) -> int:
    """Move queued reactions to a final status (guarded: only those still queued) and tell their senders once."""
    closed = 0
    for r in reactions:
        done = (db.table("reactions").update({"status": status, "fail_reason": reason}).eq("id", r["id"]).eq("status", "queued")
                .is_("batch_id", "null").execute().data)
        if done:
            closed += 1
            if notify:
                _tell_sender(db, r, notify, reason)
    return closed


# ---------------------------------------------------------------- sending
def _queued(db: Client, recipient_id: str) -> list[dict]:
    return (
        db.table("reactions").select("*").eq("recipient_id", recipient_id).eq("status", "queued").is_("batch_id", "null")
        .order("queued_at").execute().data
    )


def process_recipient(db: Client, wa: Messenger, settings: Settings, recipient: dict, now: datetime, counts: Counter) -> None:
    queued = _queued(db, recipient["id"])
    if not queued:
        return
    expired = [r for r in queued if _at(r["expires_at"]) <= now]
    counts["love_expired"] += _close(db, expired, "expired", "expired", "love_not_delivered")
    queued = [r for r in queued if r not in expired]
    row = _settings_row(db, recipient["id"])
    if queued and not love_active(db, settings, recipient, row):
        if settings.love_loop_enabled:     # with the server switch off nothing is touched: it may be switched on again
            counts["love_skipped"] += _close(db, queued, "skipped", _skip_reason(db, recipient, row), "love_not_delivered")
        return
    if not queued or not any(_at(r["due_at"]) <= now for r in queued):
        return
    if not window_open(db, recipient["id"], now):
        return                              # waits for the parent's next reply (flush_recipient), or expires after 48 hours
    if in_quiet_hours(row, recipient.get("timezone"), now) or sent_today(db, recipient, now) >= row["daily_cap"]:
        return
    _send_batch(db, wa, recipient, queued, now, counts)


def _skip_reason(db: Client, recipient: dict, row: dict | None) -> str:
    if row and row["parent_opted_out_at"]:
        return "parent_opted_out"
    if not row or not row["enabled"]:
        return "love_off"
    return "parent_not_reachable"


def _send_batch(db: Client, wa: Messenger, recipient: dict, queued: list[dict], now: datetime, counts: Counter) -> None:
    batch = str(uuid.uuid4())
    ids = [r["id"] for r in queued]
    claimed = db.table("reactions").update({"batch_id": batch}).in_("id", ids).eq("status", "queued").is_("batch_id", "null").execute().data
    if not claimed:
        return                              # another tick or webhook took them
    claimed.sort(key=lambda r: r["queued_at"])
    sender_ids = list({r["sender_id"] for r in claimed if r["sender_id"]})
    names = _names(db, recipient["circle_id"], sender_ids) if sender_ids else {}
    body = build_message(claimed, names, recipient.get("language"))
    try:
        message_id = wa.send_text(recipient["whatsapp_phone"], body)
    except WhatsAppError:
        # give the claim back: the next tick tries again (the 48-hour expiry is the limit)
        db.table("reactions").update({"batch_id": None}).eq("batch_id", batch).eq("status", "queued").execute()
        counts["love_send_errors"] += 1
        return
    db.table("reactions").update({
        "status": "sent", "wamid": message_id, "sent_at": _iso(now), "in_window": True, "category": "service",
    }).eq("batch_id", batch).execute()
    counts["love_sent"] += 1
    log.info("love message sent (%d reactions, in_window=true, category=service)", len(claimed))   # counts only, never the text


def run(db: Client, wa: Messenger, settings: Settings, now: datetime, in_scope: Callable[[str], bool], counts: Counter) -> None:
    """The scheduler step: expire, skip and send for every parent with queued reactions."""
    ids = sorted({r["recipient_id"] for r in db.table("reactions").select("recipient_id").eq("status", "queued").is_("batch_id", "null").execute().data})
    for recipient_id in ids:
        if not in_scope(recipient_id):
            continue
        try:
            recipient = _first(db.table("care_recipients").select("*").eq("id", recipient_id))
            if recipient:
                process_recipient(db, wa, settings, recipient, now, counts)
        except Exception as exc:  # noqa: BLE001
            log.error("love loop failed for parent %s (%s)", recipient_id, type(exc).__name__)
            counts["love_errors"] += 1


# ---------------------------------------------------------------- what the parent writes
def _clean_body(body: str) -> str:
    return CONTROL.sub(" ", unicodedata.normalize("NFC", body)).strip()[:MAX_BODY]


def _kind_of(message_type: str, body: str | None, tokens: list[str]) -> str:
    if message_type in {"button", "interactive"}:
        return "button"
    if message_type == "text" and body:
        return "text" if tokens else "emoji"       # no letters or digits at all: a heart, a folded-hands, a thumbs-up
    return "other"


def heart_command(tokens: list[str]) -> str | None:
    """'off' or 'on' when the parent wrote a short NO HEARTS / HEARTS (or the Hindi equivalent), else None.
    A chat sentence that merely mentions a heart is not a command."""
    words = set(tokens)
    if not tokens or len(tokens) > MAX_HEART_COMMAND_WORDS or not words & HEART_WORDS:
        return None
    off, on = bool(words & OFF_WORDS), bool(words & ON_WORDS)
    if off and not on:
        return "off"
    if on and not off:
        return "on"
    return "on" if not off and not on and len(tokens) == 1 else None


def apply_heart_command(db: Client, recipient: dict, command: str, now: datetime) -> bool:
    """The parent switched the love messages off (or back on) for this profile. Only these: reminders and check-ins are untouched.
    True when something changed (so the family was told and the parent should get a confirmation)."""
    if command == "off":
        changed = (
            db.table("love_settings").update({"parent_opted_out_at": _iso(now)}).eq("recipient_id", recipient["id"])
            .is_("parent_opted_out_at", "null").execute().data
        )
        if not changed and _settings_row(db, recipient["id"]):
            return False                    # already off: a repeated message changes and tells no one
        if not changed:
            db.table("love_settings").upsert({"recipient_id": recipient["id"], "enabled": False, "parent_opted_out_at": _iso(now)},
                                             on_conflict="recipient_id", ignore_duplicates=True).execute()
        _close(db, _queued(db, recipient["id"]), "skipped", "parent_opted_out", None)
        notify_circle(db, recipient["circle_id"], "love_parent_opted_out", {"recipient_id": recipient["id"]})
        return True
    changed = (
        db.table("love_settings").update({"parent_opted_out_at": None}).eq("recipient_id", recipient["id"])
        .not_.is_("parent_opted_out_at", "null").execute().data
    )
    if changed:
        notify_circle(db, recipient["circle_id"], "love_parent_opted_in", {"recipient_id": recipient["id"]})
    return bool(changed)


def handle_heart_command(db: Client, wa: Messenger, sender: str, command: str, now: datetime) -> None:
    """NO HEARTS / HEARTS from a phone number: applied to every parent profile on that number, answered ONCE, politely."""
    first_language = None
    for recipient in db.table("care_recipients").select("*").eq("whatsapp_phone", sender).order("created_at").execute().data:
        if apply_heart_command(db, recipient, command, now) and first_language is None:
            first_language = recipient.get("language") or "en"
    if first_language is None:
        return
    try:
        wa.send_text(sender, copy(first_language)["hearts_off" if command == "off" else "hearts_on"])   # the parent just wrote: window open
    except WhatsAppError:
        log.warning("could not send the hearts confirmation reply")


def record_inbound(db: Client, wa: Messenger, settings: Settings, recipient: dict, message: dict, tokens: list[str], now: datetime) -> None:
    """Store what the parent sent (text, emoji, a tap) for the families who use the love loop, link it to the reaction it answers,
    and give any reaction that was waiting for the window its chance to go out. Voice notes and photos are only noted, never kept."""
    if not love_active(db, settings, recipient):
        return
    message_id = message.get("id")
    kind = _kind_of(str(message.get("type")), (message.get("text") or {}).get("body") if isinstance(message.get("text"), dict) else None, tokens)
    body = None
    if kind in {"text", "emoji"}:
        body = _clean_body(message["text"]["body"]) or None
    reaction_id = None
    if kind in {"text", "emoji"}:
        since = now - timedelta(minutes=settings.love_reply_link_minutes)
        recent = (
            db.table("reactions").select("id").eq("recipient_id", recipient["id"]).in_("status", ["sent", "delivered", "read"])
            .gte("sent_at", _iso(since)).order("sent_at", desc=True).limit(1).execute().data
        )
        reaction_id = recent[0]["id"] if recent else None
    inserted = db.table("parent_messages").upsert(
        {"recipient_id": recipient["id"], "wamid": message_id, "kind": kind, "body": body, "reaction_id": reaction_id},
        on_conflict="wamid", ignore_duplicates=True,
    ).execute().data
    if inserted and kind in {"text", "emoji"}:
        _notice_parent_message(db, recipient, now)
    flush_recipient(db, wa, settings, recipient, now)


def _notice_parent_message(db: Client, recipient: dict, now: datetime) -> None:
    """One in-app notice per parent per 30 minutes, however many emoji arrive."""
    members = db.table("care_circle_members").select("user_id").eq("circle_id", recipient["circle_id"]).limit(1).execute().data
    if not members:
        return
    recent = (
        db.table("notifications").select("id").eq("user_id", members[0]["user_id"]).eq("type", "parent_message")
        .contains("payload", {"recipient_id": recipient["id"]}).gte("created_at", _iso(now - PARENT_MESSAGE_NOTICE_GAP)).limit(1).execute().data
    )
    if not recent:
        notify_circle(db, recipient["circle_id"], "parent_message", {"recipient_id": recipient["id"]})


def flush_recipient(db: Client, wa: Messenger, settings: Settings, recipient: dict, now: datetime) -> None:
    """The parent just wrote: reactions that were waiting for an open window can go out now (same rules as the scheduler)."""
    try:
        process_recipient(db, wa, settings, recipient, now, Counter())
    except Exception as exc:  # noqa: BLE001
        log.error("love loop flush failed (%s)", type(exc).__name__)   # never fail the webhook: the next tick retries


# ---------------------------------------------------------------- delivery statuses from WhatsApp
def update_status(db: Client, message_id: str, status: str, when: datetime) -> None:
    """sent -> delivered -> read, forward only: a replayed or out-of-order status never moves a reaction backwards."""
    if status == "delivered":
        db.table("reactions").update({"status": "delivered", "delivered_at": _iso(when)}).eq("wamid", message_id).eq("status", "sent").execute()
    elif status == "read":
        db.table("reactions").update({"status": "read", "read_at": _iso(when)}).eq("wamid", message_id).in_("status", ["sent", "delivered"]).execute()
        db.table("reactions").update({"delivered_at": _iso(when)}).eq("wamid", message_id).is_("delivered_at", "null").execute()


def mark_failed(db: Client, message_id: str) -> None:
    """WhatsApp could not deliver a love message: tell each sender once."""
    failed = db.table("reactions").update({"status": "failed", "fail_reason": "whatsapp_failed"}).eq("wamid", message_id).eq("status", "sent").execute().data
    for r in failed or []:
        _tell_sender(db, r, "love_not_delivered", "whatsapp_failed")
