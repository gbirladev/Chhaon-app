"""WhatsApp alerts to the caregiver.

Notifications are created elsewhere (database triggers, scheduler, WhatsApp replies). This module turns the ones
that matter into WhatsApp messages: an outbox that every scheduler tick drains.

Safety rules:
  * double opt-in: alerts go only to a number that replied "Yes" to our confirmation message;
  * never to a number that said STOP / No (phone_opt_outs), and STOP turns alerts off;
  * claimed in the database before sending (no duplicates), retried a few times, never sent late (lookback window);
  * at most `alerts_per_hour` per caregiver (a bad hour must not become a flood); the rest wait (deferred, not dropped)
    until the hour frees up, as long as they are inside the lookback window;
  * messages carry first names and medicine names only; logs carry neither.
"""

import logging
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from supabase import Client

from app.config import Settings
from app.services.messaging import call_name, clean, phone_opted_out
from app.whatsapp import templates
from app.whatsapp.client import Messenger, WhatsAppError

log = logging.getLogger("carelink.alerts")
ALERT_TYPES = set(templates.ALERT_TEMPLATES)

FALLBACK = {
    "en": {"parent": "your parent", "medicine": "a medicine", "time": "earlier today", "doctor": "the doctor"},
    "hi": {"parent": "आपके माता-पिता", "medicine": "एक दवा", "time": "आज", "doctor": "डॉक्टर"},
}


def _now() -> datetime:
    return datetime.now(UTC)


def _clock(moment: datetime, tz_name: str, language: str) -> str:
    try:
        local = moment.astimezone(ZoneInfo(tz_name))
    except ZoneInfoNotFoundError:
        local = moment.astimezone(ZoneInfo("Asia/Kolkata"))
    hour, minute = local.hour, local.minute
    time = f"{hour % 12 or 12}:{minute:02d}"
    if language == "hi":
        part = "सुबह" if 4 <= hour < 12 else "दोपहर" if 12 <= hour < 16 else "शाम" if 16 <= hour < 20 else "रात"
        return f"{part} {time}"
    return f"{time} {'AM' if hour < 12 else 'PM'}"


# ---------------------------------------------------------------- opt-in
def request_opt_in(db: Client, wa: Messenger, settings: Settings, user_id: str, phone: str) -> str:
    """Asks this number to confirm. Returns: sent | opted_out | too_soon | too_many."""
    if phone_opted_out(db, phone):
        return "opted_out"  # that number said STOP/No: only it can lift that (START or Yes)
    claim = db.rpc("claim_consent_request", {
        "p_phone": phone, "p_user": user_id,
        "p_window": f"{settings.consent_resend_minutes} minutes", "p_daily_cap": settings.consent_requests_per_day,
    }).execute().data
    if claim["status"] != "ok":
        return claim["status"]

    profile = db.table("profiles").select("id, name, language").eq("id", user_id).single().execute().data
    first_name = ((profile.get("name") or "").split() or ["there"])[0][:40]
    # A new number is a new person: alerts stay OFF until THIS number confirms.
    db.table("profiles").update({
        "alert_phone": phone, "alert_requested_at": _now().isoformat(), "alert_confirmed_at": None, "alert_consent_at": None,
    }).eq("id", user_id).execute()
    try:
        wa.send_template(phone, templates.alert_optin(profile["language"], first_name, profile["id"]))
    except Exception:
        db.table("consent_requests").delete().eq("id", claim["id"]).execute()
        raise
    return "sent"


# ---------------------------------------------------------------- delivery
def deliver_pending_alerts(db: Client, wa: Messenger, settings: Settings, now: datetime | None = None, in_scope=lambda _rid: True) -> dict[str, int]:
    now = now or _now()
    counts = {"alerts_sent": 0, "alerts_skipped": 0, "alerts_failed": 0, "alerts_deferred": 0}
    since = (now - timedelta(hours=settings.alerts_lookback_hours)).isoformat()
    outbox = (
        db.table("notifications").select("id, user_id, type, payload, whatsapp_attempts")
        .is_("whatsapp_sent_at", "null").is_("whatsapp_skipped_at", "null")
        .in_("type", sorted(ALERT_TYPES)).gte("created_at", since).order("created_at").limit(200).execute().data
    )
    if not outbox:
        return counts
    users = {u["id"]: u for u in db.table("profiles").select("id, language, alert_phone, alert_consent_at").in_("id", sorted({n["user_id"] for n in outbox})).execute().data}

    for n in outbox:
        payload = n["payload"] or {}
        if not in_scope(payload.get("recipient_id")):
            continue
        profile = users.get(n["user_id"])
        if not profile or not profile["alert_consent_at"] or not profile["alert_phone"] or phone_opted_out(db, profile["alert_phone"]):
            _skip(db, n["id"])
            counts["alerts_skipped"] += 1
            continue
        hour_ago = (now - timedelta(hours=1)).isoformat()
        recent = db.table("notifications").select("id", count="exact").eq("user_id", n["user_id"]).gte("whatsapp_sent_at", hour_ago).execute().count or 0
        if recent >= settings.alerts_per_hour:
            counts["alerts_deferred"] += 1  # wait for the hour to free up (the lookback window still bounds how late)
            continue

        claimed = db.table("notifications").update({"whatsapp_sent_at": now.isoformat()}).eq("id", n["id"]).is_("whatsapp_sent_at", "null").execute().data
        if not claimed:
            continue
        try:
            wa.send_template(profile["alert_phone"], templates.caregiver_alert(n["type"], profile["language"], _params(db, n["type"], payload, profile["language"], now)))
            counts["alerts_sent"] += 1
        except (WhatsAppError, Exception):  # noqa: BLE001  any failure hands the alert back for a retry
            attempts = (n["whatsapp_attempts"] or 0) + 1
            patch = {"whatsapp_sent_at": None, "whatsapp_attempts": attempts}
            if attempts >= settings.alerts_max_attempts:
                patch["whatsapp_skipped_at"] = now.isoformat()
            db.table("notifications").update(patch).eq("id", n["id"]).execute()
            counts["alerts_failed"] += 1
            log.warning("could not deliver an alert (attempt %s)", attempts)
    return counts


def _skip(db: Client, notification_id: str) -> None:
    db.table("notifications").update({"whatsapp_skipped_at": _now().isoformat()}).eq("id", notification_id).execute()


def _params(db: Client, type_: str, payload: dict, language: str, now: datetime | None = None) -> list[str]:
    fb = FALLBACK.get(language, FALLBACK["en"])
    parent, tz = fb["parent"], "Asia/Kolkata"
    if payload.get("recipient_id"):
        r = db.table("care_recipients").select("name, call_name, timezone").eq("id", payload["recipient_id"]).maybe_single().execute()
        if r and r.data:
            parent, tz = clean(call_name(r.data, language)), r.data["timezone"]
    medicine = fb["medicine"]
    if payload.get("medicine_id"):
        m = db.table("medicines").select("name").eq("id", payload["medicine_id"]).maybe_single().execute()
        if m and m.data:
            medicine = clean(m.data["name"])
    when = fb["time"]
    if payload.get("dose_event_id"):
        d = db.table("dose_events").select("scheduled_at").eq("id", payload["dose_event_id"]).maybe_single().execute()
        if d and d.data:
            when = _clock(datetime.fromisoformat(d.data["scheduled_at"]), tz, language)

    if type_ == "dose_missed":
        return [parent, medicine, when]
    if type_ == "appointment_reminder":
        from app.services.appointments import (  # late import: appointments imports this module
            parent_zone,
            when_text,
        )

        doctor, visit_text = fb["doctor"], fb["time"]
        if payload.get(f"when_{language}") or payload.get("when_en"):
            # Worded when the reminder went to the parent, in the parent's zone: never recomputed from "now".
            visit_text = clean(payload.get(f"when_{language}") or payload.get("when_en"), 60)
            if payload.get("doctor"):
                doctor = clean(payload["doctor"])
        elif payload.get("appointment_id"):
            a = db.table("appointments").select("doctor_name, datetime").eq("id", payload["appointment_id"]).maybe_single().execute()
            if a and a.data:
                doctor = clean(a.data["doctor_name"])
                visit_text = when_text(datetime.fromisoformat(a.data["datetime"]), (now or _now()).astimezone(parent_zone(tz)), language)
        return [parent, doctor, visit_text]
    if type_ in {"refill_needed", "out_of_stock", "reminder_not_sent"}:
        return [medicine, parent]
    return [parent]
