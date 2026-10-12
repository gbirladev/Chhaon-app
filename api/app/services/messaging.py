"""Outbound WhatsApp messages: consent request, dose reminders, daily check-ins.

Rules enforced here: never message a parent who has not opted in (or who said STOP),
and claim work in the database before sending so retries / parallel workers cannot double-send.
"""

import logging
import re
from datetime import UTC, date, datetime

from supabase import Client

from app.config import Settings
from app.privacy import mask_phone
from app.whatsapp import templates
from app.whatsapp.client import Messenger

log = logging.getLogger("carelink.messaging")

CALL_NAMES = {
    "en": {"father": "Papa", "mother": "Mummy"},
    "hi": {"father": "पापा", "mother": "मम्मी"},
}
INSTRUCTIONS = {
    "en": {"after_food": "After food", "before_food": "Before food", "with_food": "With food", None: "As directed"},
    "hi": {"after_food": "खाने के बाद", "before_food": "खाने से पहले", "with_food": "खाने के साथ", None: "निर्देशानुसार"},
}
HI_UNITS = {"tablet": "गोली", "tablets": "गोलियाँ", "capsule": "कैप्सूल", "capsules": "कैप्सूल", "drops": "बूँदें",
            "units": "यूनिट", "puffs": "पफ़", "tsp": "चम्मच"}


def clean(text: str | None, limit: int = 40) -> str:
    """Template parameters come from caregivers: strip control characters and newlines, collapse spaces, shorten."""
    value = re.sub(r"[\x00-\x1f\x7f\u2028\u2029]+", " ", text or "")
    value = re.sub(r" {2,}", " ", value).strip()
    return value[:limit] or "-"


def phone_opted_out(db: Client, phone: str | None) -> bool:
    """True if this PHONE NUMBER ever said STOP (or declined) and has not lifted it. Survives editing or re-adding a parent."""
    if not phone:
        return False
    return bool(db.table("phone_opt_outs").select("phone").eq("phone", phone).execute().data)


def allowed(db: Client, recipient: dict) -> bool:
    """May we message this parent right now? Consent given, not opted out on the record, and not opted out as a phone number."""
    return can_message(recipient) and not phone_opted_out(db, recipient["whatsapp_phone"])


def can_message(recipient: dict) -> bool:
    return bool(recipient.get("whatsapp_phone") and recipient.get("consent_at") and not recipient.get("opted_out_at"))


def call_name(recipient: dict, language: str) -> str:
    """What to call the parent in their messages: the name the caregiver chose ("Mom", "Dadi"), else the name they typed."""
    return (recipient.get("call_name") or "").strip() or recipient["name"]


def localize_dose(dose: str | None, language: str) -> str:
    """Stored as canonical English ('500 mg', '1 tablet'); show the unit in the parent's language."""
    if not dose:
        return "-"
    if language != "hi":
        return dose
    amount, _, unit = dose.partition(" ")
    return f"{amount} {HI_UNITS.get(unit, unit)}".strip()


def _now() -> datetime:
    return datetime.now(UTC)


# ---------------------------------------------------------------- consent
def send_consent_request(db: Client, wa: Messenger, settings: Settings, recipient_id: str, requested_by: str) -> str:
    """Asks the parent to agree to messages. Returns: sent | already_consented | opted_out | too_soon | too_many | no_phone."""
    r = db.table("care_recipients").select("*").eq("id", recipient_id).single().execute().data
    if not r.get("whatsapp_phone"):
        return "no_phone"
    if r.get("opted_out_at") or phone_opted_out(db, r["whatsapp_phone"]):
        return "opted_out"  # the parent said STOP; only they can restart (START, or Yes on a consent message)
    if r.get("consent_at"):
        return "already_consented"

    # Limits a caregiver cannot reset, decided atomically in the database (see claim_consent_request):
    #  - one request per phone number per window, across ALL families;
    #  - a daily cap per caregiver.
    phone = r["whatsapp_phone"]
    claim = db.rpc("claim_consent_request", {
        "p_phone": phone, "p_user": requested_by,
        "p_window": f"{settings.consent_resend_minutes} minutes", "p_daily_cap": settings.consent_requests_per_day,
    }).execute().data
    if claim["status"] != "ok":
        return claim["status"]
    logged = claim["id"]

    profile = db.table("profiles").select("name").eq("id", requested_by).maybe_single().execute()
    caregiver = clean((profile.data or {}).get("name") if profile else None, 40) if profile and profile.data and profile.data.get("name") else "Your family"
    language = r["language"]
    template = templates.consent_request(language, clean(call_name(r, language)), caregiver, r["id"])
    try:
        wa.send_template(phone, template)
    except Exception:
        db.table("consent_requests").delete().eq("id", logged).execute()
        raise
    db.table("care_recipients").update({"consent_requested_at": _now().isoformat()}).eq("id", r["id"]).execute()
    return "sent"


# ---------------------------------------------------------------- dose reminder
def _signed_photo_url(db: Client, path: str | None, recipient_id: str) -> str | None:
    # The path is caregiver-supplied and signed with elevated rights: only this parent's own folder is allowed.
    if not path or not path.startswith(f"{recipient_id}/") or ".." in path:
        return None
    try:
        result = db.storage.from_("medicine-photos").create_signed_url(path, 600)
        return result.get("signedURL") or result.get("signedUrl")
    except Exception:  # noqa: BLE001  a missing photo must never block a reminder
        log.warning("could not sign medicine photo url")
        return None


def send_dose_reminder(db: Client, wa: Messenger, dose_event_id: str, now: datetime | None = None) -> str:
    """Sends the reminder for one dose event. Returns: sent | skipped | not_allowed."""
    ev = (
        db.table("dose_events")
        .select("id, status, medicine_schedules(medicines(name, dosage, instructions, photo_url, care_recipients(*)))")
        .eq("id", dose_event_id).single().execute().data
    )
    medicine = ev["medicine_schedules"]["medicines"]
    r = medicine["care_recipients"]
    if not allowed(db, r):
        return "not_allowed"

    # Claim: only one worker can move pending -> sent.
    claimed = (
        db.table("dose_events").update({"status": "sent", "sent_at": (now or _now()).isoformat()})
        .eq("id", ev["id"]).eq("status", "pending").execute().data
    )
    if not claimed:
        return "skipped"

    language = r["language"]
    template = templates.dose_reminder(
        language, clean(call_name(r, language)), clean(medicine["name"]), clean(localize_dose(medicine["dosage"], language), 30),
        INSTRUCTIONS[language if language in INSTRUCTIONS else "en"].get(medicine["instructions"], "-"),
        ev["id"], _signed_photo_url(db, medicine["photo_url"], r["id"]),
    )
    try:
        message_id = wa.send_template(r["whatsapp_phone"], template)
    except Exception:
        db.table("dose_events").update({"status": "pending", "sent_at": None}).eq("id", ev["id"]).execute()
        raise
    db.table("dose_events").update({"whatsapp_message_id": message_id}).eq("id", ev["id"]).execute()
    log.info("dose reminder sent to %s", mask_phone(r["whatsapp_phone"]))
    return "sent"


# ---------------------------------------------------------------- daily check-in
def send_checkin(db: Client, wa: Messenger, recipient_id: str, local_date: date, now: datetime | None = None) -> str:
    """Sends the 'are you okay?' check-in for the parent's local date. Returns: sent | skipped | not_allowed."""
    r = db.table("care_recipients").select("*").eq("id", recipient_id).single().execute().data
    if not allowed(db, r):
        return "not_allowed"
    db.table("checkins").upsert(
        {"recipient_id": recipient_id, "date": local_date.isoformat()},
        on_conflict="recipient_id,date", ignore_duplicates=True,
    ).execute()
    row = db.table("checkins").select("id").eq("recipient_id", recipient_id).eq("date", local_date.isoformat()).single().execute().data
    claimed = (
        db.table("checkins").update({"status": "sent", "sent_at": (now or _now()).isoformat()})
        .eq("id", row["id"]).eq("status", "pending").execute().data
    )
    if not claimed:
        return "skipped"
    language = r["language"]
    try:
        wa.send_template(r["whatsapp_phone"], templates.daily_checkin(language, clean(call_name(r, language)), row["id"]))
    except Exception:
        db.table("checkins").update({"status": "pending", "sent_at": None}).eq("id", row["id"]).execute()
        raise
    return "sent"
