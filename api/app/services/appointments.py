"""Appointment reminders to the parent on WhatsApp (utility template, no buttons).

Two reminders per visit, all clock logic in the PARENT's timezone:
  * day before: at/after 18:00 the evening before (only until the morning reminder time);
  * morning: at/after 07:30 on the visit day, only while the visit is at least 30 minutes away.
A reminder is claimed first (reminded_*_at set atomically), sent, and handed back if the send fails,
so the next tick retries until the visit is too close. Each sent reminder also tells the family (bell + caregiver alert).
Only appointments with remind = true are touched. Logs carry no names or phone numbers.
"""

import logging
from collections.abc import Callable
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from supabase import Client

from app.privacy import mask_phone
from app.services import alerts, messaging
from app.services.notify import notify_circle
from app.whatsapp import templates
from app.whatsapp.client import Messenger

log = logging.getLogger("carelink.appointments")

DAY_BEFORE_AT = time(18, 0)
MORNING_AT = time(7, 30)
MIN_GAP_DAY_BEFORE = timedelta(hours=3)
MIN_LEAD_MORNING = timedelta(minutes=30)
DAY_BEFORE_QUIET = timedelta(hours=2)  # no day-before message in the 2 hours before the morning one (it would repeat it)

HI_MONTHS = ["जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"]
EN_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def parent_zone(tz_name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(tz_name or "Asia/Kolkata")
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo("Asia/Kolkata")


def when_text(visit: datetime, now_local: datetime, language: str) -> str:
    """'tomorrow at 11:00 AM' / 'आज सुबह 11:00'. `now_local` is now in the parent's timezone; the visit is shown on that clock."""
    zone = now_local.tzinfo
    visit_local = visit.astimezone(zone)
    clock = alerts._clock(visit_local, getattr(zone, "key", "Asia/Kolkata"), language)
    days = (visit_local.date() - now_local.date()).days
    hindi = language == "hi"
    if days == 0:
        return f"आज {clock}" if hindi else f"today at {clock}"
    if days == 1:
        return f"कल {clock}" if hindi else f"tomorrow at {clock}"
    if hindi:
        return f"{visit_local.day} {HI_MONTHS[visit_local.month - 1]} को {clock}"
    return f"on {visit_local.day} {EN_MONTHS[visit_local.month - 1]} at {clock}"


def _at(day, clock: time, zone) -> datetime:
    return datetime.combine(day, clock, tzinfo=zone)


def due_kind(row: dict, now: datetime, zone: ZoneInfo) -> str | None:
    """Which reminder (if any) is due now: 'day_before' | 'morning' | None."""
    visit = datetime.fromisoformat(row["datetime"]).astimezone(UTC)
    if visit <= now:
        return None
    visit_local = visit.astimezone(zone)
    evening = _at(visit_local.date() - timedelta(days=1), DAY_BEFORE_AT, zone)
    morning = _at(visit_local.date(), MORNING_AT, zone)
    created = row.get("created_at")  # not stored yet; when it is, a visit booked after the evening gets only the morning one
    booked_late = bool(created) and datetime.fromisoformat(created) > evening
    if (not row.get("reminded_day_before_at") and evening <= now < morning - DAY_BEFORE_QUIET
            and visit - evening >= MIN_GAP_DAY_BEFORE and not booked_late):
        return "day_before"
    if not row.get("reminded_morning_at") and now >= morning and visit - now >= MIN_LEAD_MORNING:
        return "morning"
    return None


def remind_due(db: Client, wa: Messenger, now: datetime, page: Callable, in_scope: Callable[[str], bool]) -> dict[str, int]:
    """One pass. `page` pages through a query (scheduler._all). Returns counters."""
    counts = {"appointment_reminders": 0, "appointment_errors": 0}
    horizon = now + timedelta(days=2)
    rows = page(lambda: db.table("appointments").select("*, care_recipients(*)").eq("remind", True)
                .gt("datetime", now.isoformat()).lte("datetime", horizon.isoformat()))
    for row in rows:
        parent = row["care_recipients"]
        if not in_scope(row["recipient_id"]):
            continue
        zone = parent_zone(parent.get("timezone"))
        kind = due_kind(row, now, zone)
        if not kind or not messaging.allowed(db, parent):
            continue  # not agreed / STOP: nothing is sent and nothing is claimed
        column = "reminded_day_before_at" if kind == "day_before" else "reminded_morning_at"
        seen = row["datetime"]  # claim only if the visit time is still the one this decision was based on
        claimed = db.table("appointments").update({column: now.isoformat()}).eq("id", row["id"]).eq("datetime", seen).is_(column, "null").execute().data
        if not claimed:
            continue
        language = parent["language"]
        visit = datetime.fromisoformat(seen)
        local_now = now.astimezone(zone)
        when = {"en": when_text(visit, local_now, "en"), "hi": when_text(visit, local_now, "hi")}
        template = templates.appointment_reminder(
            language, messaging.clean(messaging.call_name(parent, language)), messaging.clean(row["doctor_name"]),
            when[language if language in when else "en"],
        )
        try:
            wa.send_template(parent["whatsapp_phone"], template)
        except Exception:  # noqa: BLE001  any failure hands the reminder back for a retry
            db.table("appointments").update({column: None}).eq("id", row["id"]).execute()
            counts["appointment_errors"] += 1
            log.warning("appointment reminder failed, will retry")
            continue
        counts["appointment_reminders"] += 1
        log.info("appointment reminder sent to %s", mask_phone(parent["whatsapp_phone"]))
        try:
            notify_circle(db, parent["circle_id"], "appointment_reminder",
                          {"recipient_id": row["recipient_id"], "appointment_id": row["id"], "kind": kind,
                           "doctor": messaging.clean(row["doctor_name"]), "when_en": when["en"], "when_hi": when["hi"]})
        except Exception as exc:  # noqa: BLE001
            log.error("could not notify the family about an appointment reminder (%s)", type(exc).__name__)
    return counts
