"""The 5-minute tick: creates dose events, sends due reminders, marks unanswered ones missed,
sends daily check-ins and alerts caregivers.

Every step is idempotent and claims rows in the database before acting (status transitions with a
`where status = ...` guard), so overlapping ticks or retries never double-send or double-alert.
One failing item never stops the rest of the tick, and one failing step never stops the other steps.
"""

import logging
from collections import Counter
from collections.abc import Callable, Iterable
from contextvars import ContextVar
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from supabase import Client

from app.config import Settings
from app.services import alerts, appointments, escalation, messaging
from app.services.notify import notify_circle
from app.whatsapp.client import Messenger

log = logging.getLogger("carelink.scheduler")

# medicine id + parent's circle and opt-out state, for alerts
DOSE_CONTEXT = "id, sent_at, scheduled_at, medicine_schedules(medicines(id, recipient_id, care_recipients(circle_id, opted_out_at)))"
PAGE = 1000  # PostgREST returns at most 1000 rows per request


# Tests set this so a tick can never touch anyone else's data on a shared database. Production leaves it None.
DEFAULT_SCOPE: set[str] | None = None
_scope: ContextVar[set[str] | None] = ContextVar("scheduler_scope", default=None)


def _in_scope(recipient_id: str) -> bool:
    scope = _scope.get()
    return scope is None or recipient_id in scope


def run_tick(
    db: Client, wa: Messenger, settings: Settings, now: datetime | None = None, only_recipients: Iterable[str] | None = None
) -> dict[str, int]:
    """Runs all scheduler steps once. Returns counters, e.g. {'generated': 4, 'reminders_sent': 1, ...}.
    `only_recipients` limits every step to those parents (None = everyone)."""
    now = (now or datetime.now(UTC)).astimezone(UTC)
    scope = set(only_recipients) if only_recipients is not None else DEFAULT_SCOPE
    token = _scope.set(scope)
    try:
        return _run_steps(db, wa, settings, now)
    finally:
        _scope.reset(token)


def _run_steps(db: Client, wa: Messenger, settings: Settings, now: datetime) -> dict[str, int]:
    counts: Counter[str] = Counter()
    steps = (_generate, _send_due_doses, _expire_unsent_doses, _mark_missed_doses, _send_checkins, _escalate, _mark_missed_checkins, _remind_appointments, _deliver_alerts)
    for step in steps:
        try:
            step(db, wa, settings, now, counts)
        except Exception as exc:  # noqa: BLE001
            log.error("scheduler step %s failed (%s)", step.__name__, _reason(exc))
            counts["step_errors"] += 1
    return dict(counts)


def _reason(exc: Exception) -> str:
    """Error type plus the database error code and, for setup problems only, its message.
    Messages of other errors can contain row values (health data), so they are never logged."""
    code = str(getattr(exc, "code", "") or "")
    message = str(getattr(exc, "message", "") or "")
    setup_problem = not code or code.startswith(("PGRST", "42")) or "jwt" in message.lower() or "api key" in message.lower()
    parts = [type(exc).__name__]
    if code:
        parts.append(f"code={code}")
    if setup_problem and message:
        parts.append(message[:200])
    return " ".join(parts)


def _paused_recipient_ids(db: Client) -> set[str]:
    """Parents whose reminders are paused because they are above their owner's plan limit (migration 0027)."""
    return {row["recipient_id"] for row in (db.rpc("paused_recipients").execute().data or [])}


def _iso(value: datetime) -> str:
    return value.isoformat()


def _all(build_query: Callable[[], object]) -> list[dict]:
    """Every row of a query, page by page (a plain select silently stops at 1000 rows)."""
    rows: list[dict] = []
    start = 0
    while True:
        batch = build_query().order("id").range(start, start + PAGE - 1).execute().data  # type: ignore[attr-defined]
        rows.extend(batch)
        if len(batch) < PAGE:
            return rows
        start += PAGE


# ---------------------------------------------------------------- 1. dose events
def _generate(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    # The grace period equals the reminder window: after downtime, doses that can still be reminded are created.
    params: dict = {"p_now": _iso(now), "p_grace": f"{settings.dose_missed_minutes} minutes"}
    scope = _scope.get()
    if scope is not None:
        params["p_recipients"] = sorted(scope)
    counts["generated"] = db.rpc("generate_dose_events", params).execute().data or 0


# ---------------------------------------------------------------- 2. reminders
def _send_due_doses(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    window_start = now - timedelta(minutes=settings.dose_missed_minutes)
    due = _all(lambda: db.table("dose_events").select("id, scheduled_at, medicine_schedules(medicines(recipient_id))").eq("status", "pending")
               .lte("scheduled_at", _iso(now)).gt("scheduled_at", _iso(window_start)))
    # A time slot = one parent, one exact moment. With slot reminders on, a slot is ONE message; otherwise every dose is its own.
    slots: dict[tuple, list[str]] = {}
    for row in due:
        parent_id = row["medicine_schedules"]["medicines"]["recipient_id"]
        if not _in_scope(parent_id):
            continue
        key = (parent_id, row["scheduled_at"]) if settings.slot_reminders_enabled else (parent_id, row["scheduled_at"], row["id"])
        slots.setdefault(key, []).append(row["id"])
    for ids in slots.values():
        try:
            result = messaging.send_slot_reminder(db, wa, ids, now=now) if len(ids) > 1 else messaging.send_dose_reminder(db, wa, ids[0], now=now)
        except Exception as exc:  # noqa: BLE001
            log.error("reminder failed for %s dose(s) (%s)", len(ids), type(exc).__name__)
            counts["send_errors"] += 1  # stays pending: retried next tick until the window closes
            continue
        if result == "sent":
            counts["reminders_sent"] += 1
            if len(ids) > 1:
                counts["slot_reminders"] += 1
        elif result == "not_allowed":
            # Parent has not agreed (or said STOP): nothing is sent and it is not a "missed" dose.
            db.table("dose_events").update({"status": "skipped"}).in_("id", ids).eq("status", "pending").execute()
            counts["doses_skipped"] += len(ids)


def _expire_unsent_doses(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    """A reminder we kept failing to send. Too late to send now; tell the caregiver so they can phone."""
    cutoff = now - timedelta(minutes=settings.dose_missed_minutes)
    stale = _all(lambda: db.table("dose_events").select(DOSE_CONTEXT).eq("status", "pending").lte("scheduled_at", _iso(cutoff)))
    for row in stale:
        if not _in_scope(row["medicine_schedules"]["medicines"]["recipient_id"]):
            continue
        claimed = db.table("dose_events").update({"status": "skipped"}).eq("id", row["id"]).eq("status", "pending").execute().data
        if claimed:
            medicine = row["medicine_schedules"]["medicines"]
            notify_circle(db, medicine["care_recipients"]["circle_id"], "reminder_not_sent",
                          {"recipient_id": medicine["recipient_id"], "medicine_id": medicine["id"], "dose_event_id": row["id"]})
            counts["reminders_not_sent"] += 1


# ---------------------------------------------------------------- 3. missed doses
def _mark_missed_doses(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    cutoff = now - timedelta(minutes=settings.dose_missed_minutes)
    waiting = _all(lambda: db.table("dose_events").select(DOSE_CONTEXT).eq("status", "sent").lte("sent_at", _iso(cutoff)))
    slots: dict[tuple, list[dict]] = {}   # doses that went missed at the same moment for the same parent are ONE alert
    escalating = set(escalation.active_policies(db, settings))   # these parents are handled by the escalation ladder instead
    for row in waiting:
        if not _in_scope(row["medicine_schedules"]["medicines"]["recipient_id"]) or row["medicine_schedules"]["medicines"]["recipient_id"] in escalating:
            continue
        claimed = db.table("dose_events").update({"status": "missed"}).eq("id", row["id"]).eq("status", "sent").execute().data
        if not claimed:
            continue
        counts["doses_missed"] += 1
        medicine = row["medicine_schedules"]["medicines"]
        slots.setdefault((medicine["recipient_id"], row["scheduled_at"]), []).append(row)
    for (recipient_id, _at), rows in slots.items():
        medicine = rows[0]["medicine_schedules"]["medicines"]
        parent = medicine["care_recipients"]
        if parent["opted_out_at"]:  # a parent who said STOP is not "ignoring" us
            continue
        payload = {"recipient_id": recipient_id, "medicine_id": medicine["id"], "dose_event_id": rows[0]["id"]}
        if len(rows) > 1:
            payload["count"] = len(rows)
        notify_circle(db, parent["circle_id"], "dose_missed", payload)


# ---------------------------------------------------------------- 4. daily check-in
def _local(tz_name: str, now: datetime) -> datetime:
    try:
        return now.astimezone(ZoneInfo(tz_name))
    except ZoneInfoNotFoundError:
        return now.astimezone(ZoneInfo("Asia/Kolkata"))


def _send_checkins(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    parents = _all(lambda: db.table("care_recipients").select("id, timezone, checkin_time")
                   .not_.is_("consent_at", "null").is_("opted_out_at", "null").not_.is_("whatsapp_phone", "null"))
    paused = _paused_recipient_ids(db)
    for parent in parents:
        if not _in_scope(parent["id"]) or parent["id"] in paused:   # above the plan's parent limit: no check-in
            continue
        local_now = _local(parent["timezone"], now)
        start = datetime.combine(local_now.date(), time.fromisoformat(parent["checkin_time"]), tzinfo=local_now.tzinfo)
        if not start <= local_now < start + timedelta(minutes=settings.checkin_start_window_minutes):
            continue
        try:
            if messaging.send_checkin(db, wa, parent["id"], local_now.date(), now=now) == "sent":
                counts["checkins_sent"] += 1
        except Exception as exc:  # noqa: BLE001
            log.error("check-in failed for parent %s (%s)", parent["id"], type(exc).__name__)
            counts["send_errors"] += 1


def _escalate(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    """The escalation ladder for parents who have it switched on (see services/escalation.py)."""
    escalation.run(db, wa, settings, now, _in_scope, counts)


def _mark_missed_checkins(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    cutoff = now - timedelta(minutes=settings.checkin_missed_minutes)
    waiting = _all(lambda: db.table("checkins").select("id, care_recipients(id, circle_id, opted_out_at)")
                   .eq("status", "sent").lte("sent_at", _iso(cutoff)))
    escalating = set(escalation.active_policies(db, settings))   # these parents are handled by the escalation ladder instead
    for row in waiting:
        if not _in_scope(row["care_recipients"]["id"]) or row["care_recipients"]["id"] in escalating:
            continue
        claimed = db.table("checkins").update({"status": "missed"}).eq("id", row["id"]).eq("status", "sent").execute().data
        if not claimed:
            continue
        counts["checkins_missed"] += 1
        parent = row["care_recipients"]
        if not parent["opted_out_at"]:
            notify_circle(db, parent["circle_id"], "checkin_missed", {"recipient_id": parent["id"], "checkin_id": row["id"]})


# ---------------------------------------------------------------- 5. appointment reminders
def _remind_appointments(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    paused = _paused_recipient_ids(db)
    for key, value in appointments.remind_due(db, wa, now, _all, lambda rid: _in_scope(rid) and rid not in paused).items():
        if value:
            counts["send_errors" if key == "appointment_errors" else key] += value


# ---------------------------------------------------------------- 6. WhatsApp alerts to the caregiver
def _deliver_alerts(db: Client, wa: Messenger, settings: Settings, now: datetime, counts: Counter) -> None:
    """Runs LAST so alerts created earlier in this same tick go out in this tick."""
    for key, value in alerts.deliver_pending_alerts(db, wa, settings, now=now, in_scope=lambda rid: rid is None or _in_scope(rid)).items():
        if value:
            counts[key] += value
