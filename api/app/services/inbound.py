"""Handles everything Meta sends to our webhook: button taps, STOP/START texts, delivery failures.

Meta can deliver the same event more than once, so every event id is recorded first
(whatsapp_events primary key) and duplicates are skipped.

Error policy (important, because Meta retries any non-200 response forever):
  * a MALFORMED event (unexpected shape) is logged and skipped: retrying can never fix it, and it must not
    block the valid events in the same delivery (for example a real STOP);
  * a TRANSIENT failure (database down) releases the event record and returns an error so Meta retries.
"""

import logging
import math
import re
import unicodedata
from datetime import UTC, datetime

from postgrest.exceptions import APIError
from supabase import Client

from app.privacy import mask_phone
from app.services.notify import notify_circle
from app.whatsapp import templates
from app.whatsapp.client import Messenger, WhatsAppError

log = logging.getLogger("carelink.inbound")

# Opt-out must be easy, but a chat sentence must not opt anyone out by accident.
STOP_WORDS = {"stop", "unsubscribe", "रुको", "रुकिए", "रुकें", "रोकें"}
STOP_PHRASE_WORD = {"बंद", "बन्द"}          # "बंद करो" opts out; a bare "दरवाज़ा बंद" does not
STOP_PHRASE_VERB = {"करो", "करें", "कीजिए", "कर"}
NEGATIONS = {"dont", "don't", "not", "no", "never", "मत", "नहीं"}   # "please don't stop" is not an opt-out
START_WORDS = {"start", "शुरू", "शुरु"}
MAX_STOP_WORDS_IN_MESSAGE = 4
MAX_START_WORDS_IN_MESSAGE = 2
WORD = re.compile(r"[\w\u0900-\u097F']+")  # letters, digits and Devanagari signs (matras are not \w)
INVISIBLE = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"))
MALFORMED = (KeyError, TypeError, AttributeError, ValueError, IndexError)


def _now() -> str:
    return datetime.now(UTC).isoformat()


def _list(value) -> list:
    return value if isinstance(value, list) else []


def _dict(value) -> dict:
    return value if isinstance(value, dict) else {}


def _str(value) -> str | None:
    return value if isinstance(value, str) and value else None


def _tokens(text: str) -> list[str]:
    """Words of a message: full-width letters folded (ＳＴＯＰ), invisible characters dropped, emoji and punctuation ignored."""
    return WORD.findall(unicodedata.normalize("NFKC", text).translate(INVISIBLE).lower())


def _wants_stop(tokens: list[str]) -> bool:
    words = set(tokens)
    if not tokens or len(tokens) > MAX_STOP_WORDS_IN_MESSAGE or words & NEGATIONS:
        return False
    return bool(words & STOP_WORDS) or bool(words & STOP_PHRASE_WORD and words & STOP_PHRASE_VERB)


# ---------------------------------------------------------------- idempotency
def _claim_event(db: Client, event_id: str) -> bool:
    try:
        db.table("whatsapp_events").insert({"id": event_id}).execute()
        return True
    except APIError as exc:
        if exc.code == "23505":  # unique violation: already handled
            return False
        raise


def _release_event(db: Client, event_id: str) -> None:
    db.table("whatsapp_events").delete().eq("id", event_id).execute()


def _once(db: Client, event_id: str | None, handler) -> None:
    if not event_id or not _claim_event(db, event_id):
        return
    try:
        handler()
    except MALFORMED as exc:
        # Keep the claim: a retry would fail the same way. Class name only: a traceback can carry phone numbers.
        log.warning("skipped a malformed WhatsApp event (%s)", type(exc).__name__)
    except APIError as exc:
        if str(exc.code or "").startswith(("22", "23")):  # the database rejected the data itself: retrying cannot help
            log.warning("skipped an event the database rejected (%s)", exc.code)
            return
        _release_event(db, event_id)
        raise
    except Exception:
        _release_event(db, event_id)  # transient (database, network): let Meta's retry reach us again
        raise


# ---------------------------------------------------------------- entry point
def process_webhook(db: Client, wa: Messenger, body) -> None:
    for entry in _list(_dict(body).get("entry")):
        for change in _list(_dict(entry).get("changes")):
            value = _dict(_dict(change).get("value"))
            for message in _list(value.get("messages")):
                message = _dict(message)
                _once(db, _str(message.get("id")), lambda m=message: _handle_message(db, wa, m))
            for status in _list(value.get("statuses")):
                status = _dict(status)
                if status.get("status") == "failed":
                    # Only the numeric error codes are logged: Meta's texts can echo phone numbers.
                    codes = [_dict(e).get("code") for e in _list(status.get("errors"))]
                    log.warning("WhatsApp delivery failed, error codes=%s", codes)
                if status.get("status") == "failed" and _str(status.get("id")):
                    _once(db, f"{status['id']}:failed", lambda s=status: _handle_failed(db, s))


# ---------------------------------------------------------------- messages
def _handle_message(db: Client, wa: Messenger, message: dict) -> None:
    sender = "+" + str(message.get("from", ""))
    kind = message.get("type")

    payload = None
    if kind == "button":  # tap on a template quick-reply button
        payload = _dict(message.get("button")).get("payload")
    elif kind == "interactive":
        payload = _dict(_dict(message.get("interactive")).get("button_reply")).get("id")

    if payload is not None:
        decoded = templates.decode_payload(payload) if isinstance(payload, str) else None
        if decoded:
            _dispatch(db, wa, sender, *decoded)
        return

    if kind == "text":
        body = _dict(message.get("text")).get("body")
        tokens = _tokens(body) if isinstance(body, str) else []
        if _wants_stop(tokens):
            _opt_out(db, wa, sender)
        elif tokens and len(tokens) <= MAX_START_WORDS_IN_MESSAGE and START_WORDS & set(tokens):
            _opt_in_again(db, wa, sender)
    # Anything else (voice notes, photos, chat) is ignored for now. We never log message content.


def _dispatch(db: Client, wa: Messenger, sender: str, kind: str, entity_id, action: str) -> None:
    if kind == "consent":
        _handle_consent(db, wa, sender, str(entity_id), action)
    elif kind == "dose":
        _handle_dose(db, sender, str(entity_id), action)
    elif kind == "checkin":
        _handle_checkin(db, sender, str(entity_id), action)
    elif kind == "alert":
        _handle_alert_optin(db, wa, sender, str(entity_id), action)


def _reply(wa: Messenger, to: str, language: str, key: str) -> None:
    try:
        wa.send_text(to, templates.reply_text(language, key))
    except WhatsAppError:
        log.warning("could not send confirmation reply to %s", mask_phone(to))


def _recipient_for(db: Client, recipient_id: str, sender: str) -> dict | None:
    """The recipient, only if the sender really is that parent's number."""
    r = db.table("care_recipients").select("*").eq("id", recipient_id).maybe_single().execute()
    row = r.data if r else None
    if not row or row.get("whatsapp_phone") != sender:
        log.warning("ignored reply from unexpected number %s", mask_phone(sender))
        return None
    return row


def _lift_phone_opt_out(db: Client, phone: str) -> None:
    db.table("phone_opt_outs").delete().eq("phone", phone).execute()


def _record_phone_opt_out(db: Client, phone: str) -> None:
    """STOP belongs to the number: it outlives any parent record that carried it."""
    db.table("phone_opt_outs").upsert({"phone": phone}, on_conflict="phone", ignore_duplicates=True).execute()


def _handle_consent(db: Client, wa: Messenger, sender: str, recipient_id: str, action: str) -> None:
    r = _recipient_for(db, recipient_id, sender)
    if not r:
        return
    if action == "yes":
        _lift_phone_opt_out(db, sender)
    elif action == "no":
        _record_phone_opt_out(db, sender)
    # Each transition is claimed with a guarded UPDATE, so duplicate taps never notify or reply twice.
    if action == "yes":
        claimed = (db.table("care_recipients").update({"consent_at": _now(), "opted_out_at": None}).eq("id", r["id"])
                   .or_("consent_at.is.null,opted_out_at.not.is.null").execute().data)
        if claimed:
            notify_circle(db, r["circle_id"], "parent_consented", {"recipient_id": r["id"]})
            _reply(wa, sender, r["language"], "consent_yes")
    elif action == "no":
        claimed = db.table("care_recipients").update({"opted_out_at": _now()}).eq("id", r["id"]).is_("opted_out_at", "null").execute().data
        if claimed:
            notify_circle(db, r["circle_id"], "parent_declined", {"recipient_id": r["id"]})
            _reply(wa, sender, r["language"], "consent_no")


def _handle_alert_optin(db: Client, wa: Messenger, sender: str, profile_id: str, action: str) -> None:
    """The caregiver tapped Yes/No on our confirmation message. Only the number we asked can answer."""
    row = db.table("profiles").select("id, language, alert_phone, alert_consent_at").eq("id", profile_id).maybe_single().execute()
    profile = row.data if row else None
    if not profile or profile["alert_phone"] != sender:
        log.warning("ignored an alert reply from an unexpected number %s", mask_phone(sender))
        return
    if action == "yes":
        _lift_phone_opt_out(db, sender)
        if not profile["alert_consent_at"]:
            db.table("profiles").update({"alert_consent_at": _now(), "alert_confirmed_at": _now()}).eq("id", profile["id"]).execute()
            _reply(wa, sender, profile["language"], "alerts_on")
    elif action == "no":
        _record_phone_opt_out(db, sender)  # whoever owns this number said no: never message it again
        db.table("profiles").update({"alert_consent_at": None}).eq("id", profile["id"]).execute()
        _reply(wa, sender, profile["language"], "alerts_off")


def _units_per_dose(dosage: str | None) -> int:
    """Tablets used by one dose: '2 tablets' -> 2, '1.5 capsules' -> 2 (rounded up). Anything else
    ('500 mg', '5 ml', odd text) counts as 1. Capped so a strange value can never empty the stock."""
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s+(?:tablets?|capsules?)\s*", (dosage or "").lower())
    if not match:
        return 1
    return min(max(1, math.ceil(float(match.group(1)))), 10)


def _handle_dose(db: Client, sender: str, dose_event_id: str, action: str) -> None:
    if action != "taken":
        return
    ev = (
        db.table("dose_events")
        .select("id, status, medicine_schedules(medicines(id, dosage, care_recipients(whatsapp_phone)))")
        .eq("id", dose_event_id).maybe_single().execute()
    )
    row = ev.data if ev else None
    medicine = row["medicine_schedules"]["medicines"] if row else None
    if not row or medicine["care_recipients"]["whatsapp_phone"] != sender:
        return
    # Only a reminder we actually SENT can be confirmed (a late tap after 'missed' still counts), and only the
    # FIRST confirmation uses up stock: taps can repeat.
    first_time = (
        db.table("dose_events").update({"status": "taken", "responded_at": _now()})
        .eq("id", row["id"]).in_("status", ["sent", "missed"]).execute().data
    )
    if first_time:
        # The database raises a refill alert by itself when this drops stock to the refill level.
        db.rpc("consume_stock", {"p_medicine": medicine["id"], "p_units": _units_per_dose(medicine["dosage"])}).execute()


def _handle_checkin(db: Client, sender: str, checkin_id: str, action: str) -> None:
    row = (
        db.table("checkins")
        .select("id, status, care_recipients(whatsapp_phone, circle_id, id)")
        .eq("id", checkin_id).maybe_single().execute()
    )
    data = row.data if row else None
    if not data or data["care_recipients"]["whatsapp_phone"] != sender or action not in {"ok", "call"}:
        return
    # Atomic: only the update that actually flips the status to 'replied' is the first reply, so parallel taps
    # (Meta retries, a panicking parent) create exactly ONE call-request notification.
    first_reply = db.table("checkins").update({"status": "replied", "responded_at": _now()}).eq("id", data["id"]).neq("status", "replied").execute().data
    if action == "call" and first_reply:
        recipient = data["care_recipients"]
        notify_circle(db, recipient["circle_id"], "checkin_call_requested", {"recipient_id": recipient["id"]})


def _opt_out(db: Client, wa: Messenger, sender: str) -> None:
    _record_phone_opt_out(db, sender)
    rows = db.table("care_recipients").select("id, circle_id, language").eq("whatsapp_phone", sender).is_("opted_out_at", "null").execute().data
    for r in rows:
        db.table("care_recipients").update({"opted_out_at": _now()}).eq("id", r["id"]).execute()
        notify_circle(db, r["circle_id"], "parent_stopped", {"recipient_id": r["id"]})
    alerts = db.table("profiles").select("id, language").eq("alert_phone", sender).not_.is_("alert_consent_at", "null").execute().data
    for profile in alerts:
        db.table("profiles").update({"alert_consent_at": None}).eq("id", profile["id"]).execute()
    if rows:
        _reply(wa, sender, rows[0]["language"], "stopped")
    elif alerts:
        _reply(wa, sender, alerts[0]["language"], "alerts_off")


def _opt_in_again(db: Client, wa: Messenger, sender: str) -> None:
    """START from a parent who had opted out (STOP) or declined (No). Writing START is an explicit agreement."""
    _lift_phone_opt_out(db, sender)
    rows = (
        db.table("care_recipients").select("id, circle_id, language, consent_at")
        .eq("whatsapp_phone", sender).not_.is_("opted_out_at", "null").execute().data
    )
    for r in rows:
        patch = {"opted_out_at": None}
        if not r["consent_at"]:
            patch["consent_at"] = _now()
        db.table("care_recipients").update(patch).eq("id", r["id"]).execute()
        notify_circle(db, r["circle_id"], "parent_resumed" if r["consent_at"] else "parent_consented", {"recipient_id": r["id"]})
    # Caregiver alerts come back only for a number that confirmed them once (never for a number someone merely typed in).
    alerts = (
        db.table("profiles").select("id, language").eq("alert_phone", sender)
        .not_.is_("alert_confirmed_at", "null").is_("alert_consent_at", "null").execute().data
    )
    for profile in alerts:
        db.table("profiles").update({"alert_consent_at": _now()}).eq("id", profile["id"]).execute()
    if rows:
        _reply(wa, sender, rows[0]["language"], "resumed")
    elif alerts:
        _reply(wa, sender, alerts[0]["language"], "alerts_resumed")


# ---------------------------------------------------------------- delivery failures
def _handle_failed(db: Client, status: dict) -> None:
    ev = db.table("dose_events").select("id, medicine_schedules(medicines(care_recipients(id, circle_id)))").eq("whatsapp_message_id", status["id"]).maybe_single().execute()
    row = ev.data if ev else None
    if row:
        recipient = row["medicine_schedules"]["medicines"]["care_recipients"]
        notify_circle(db, recipient["circle_id"], "whatsapp_failed", {"recipient_id": recipient["id"], "dose_event_id": row["id"]})
