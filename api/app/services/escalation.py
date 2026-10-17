"""Escalation ladder, phase 1: nudge the parent, then alert the family only when it matters. (docs/escalation.md)

For a parent with escalation switched on (server switch AND the parent's own policy) this REPLACES the plain "mark missed after
60 minutes and alert" rule. Every unanswered prompt (a medicine time slot, or the daily check-in) is followed through steps that are
all measured from the moment the reminder was sent:

    nudge   (default 15 min for doses, 60 for check-ins)  one gentle message to the parent
    decide  (default 45 min for doses, 120 for check-ins) mark the doses / check-in missed; alert the family ONLY if
                                                          the parent has left `alert_after_unanswered` prompts in a row unanswered,
                                                          or a critical medicine is involved
    resolve any reply (Taken, Taken all, I'm okay, Call me, even a late tap) ends it; the family is told if it had been alerted

Safety rules, same as the rest of the scheduler:
  * every step is claimed with a guarded UPDATE (`... where nudged_at is null`) before anything is sent, so two overlapping ticks,
    a retry or a late tick can never send a step twice;
  * a step that comes too late is skipped, never sent late: no nudge once the alert time has passed, nothing at all for a prompt
    older than ACTION_LOOKBACK;
  * a parent who has said nothing for `silent_stop_days` days stops being nudged and alerted about; the family gets ONE summary;
  * messages and notifications carry first names and counts only, never medicine lists in logs.
"""

import logging
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

from supabase import Client

from app.config import Settings
from app.services import messaging
from app.services.notify import notify_circle
from app.whatsapp.client import Messenger

log = logging.getLogger("carelink.escalation")

DEFAULTS = {
    "dose_nudge_min": 15, "dose_alert_min": 45, "checkin_nudge_min": 60, "checkin_alert_min": 120,
    "alert_after_unanswered": 2, "silent_stop_days": 3,
}
ACTION_LOOKBACK = timedelta(hours=24)   # a long outage must not nudge or alert about yesterday


def _iso(value: datetime) -> str:
    return value.isoformat()


def _at(value: str) -> datetime:
    return datetime.fromisoformat(value).astimezone(UTC)


def _minutes(policy: dict, kind: str, step: str) -> int:
    return int(policy.get(f"{kind}_{step}_min") or DEFAULTS[f"{kind}_{step}_min"])


# ---------------------------------------------------------------- who is escalating
def active_policies(db: Client, settings: Settings, in_scope: Callable[[str], bool] = lambda _rid: True) -> dict[str, dict]:
    """Parents whose escalation is on: the server switch AND the parent's own setting."""
    if not settings.escalation_enabled:
        return {}
    rows = db.table("escalation_policies").select("*").eq("enabled", True).execute().data or []
    return {r["recipient_id"]: r for r in rows if in_scope(r["recipient_id"])}


# ---------------------------------------------------------------- the prompts of one parent
def gather_prompts(db: Client, recipient_id: str, since: datetime) -> list[dict]:
    """Everything we asked this parent since `since`, oldest first, grouped into prompts: a time slot (all doses due at the same
    moment) or a daily check-in. `responded` is true once the parent answered in any way, even late."""
    doses = (
        db.table("dose_events")
        .select("id, status, scheduled_at, sent_at, responded_at, medicine_schedules!inner(medicines!inner(id, name, dosage, critical, recipient_id))")
        .eq("medicine_schedules.medicines.recipient_id", recipient_id)
        .in_("status", ["sent", "missed", "taken", "taken_late"]).gte("sent_at", _iso(since)).order("sent_at").execute().data or []
    )
    checkins = (
        db.table("checkins").select("id, date, status, sent_at, responded_at")
        .eq("recipient_id", recipient_id).in_("status", ["sent", "missed", "replied"]).gte("sent_at", _iso(since)).execute().data or []
    )
    prompts: dict[str, dict] = {}
    for d in doses:
        medicine = d["medicine_schedules"]["medicines"]
        key = f"dose:{_at(d['scheduled_at']).strftime('%Y-%m-%dT%H:%M')}"
        p = prompts.setdefault(key, {"kind": "dose", "key": key, "base_at": _at(d["sent_at"]), "doses": [], "responded": False, "critical": False})
        p["base_at"] = min(p["base_at"], _at(d["sent_at"]))
        p["responded"] = p["responded"] or d["responded_at"] is not None
        p["critical"] = p["critical"] or bool(medicine["critical"])
        p["doses"].append({"id": d["id"], "status": d["status"], "responded": d["responded_at"] is not None,
                           "medicine_id": medicine["id"], "name": medicine["name"], "dosage": medicine["dosage"]})
    for c in checkins:
        key = f"checkin:{c['date']}"
        prompts[key] = {"kind": "checkin", "key": key, "base_at": _at(c["sent_at"]), "checkin_id": c["id"], "status": c["status"],
                        "responded": c["status"] == "replied" or c["responded_at"] is not None, "critical": False, "doses": []}
    # same moment: the medicine slot comes before the check-in sent with it
    return sorted(prompts.values(), key=lambda p: (p["base_at"], p["kind"] != "dose"))


def unanswered_run(prompts: list[dict], index: int, policies_for: Callable[[str], dict], now: datetime) -> int:
    """How many prompts in a row are unanswered, counting prompt `index` and walking back to the last one the parent answered.
    An earlier prompt counts only if it had time to be answered (its nudge time has passed)."""
    run = 1
    for earlier in reversed(prompts[:index]):
        if earlier["responded"]:
            break
        nudge = _minutes(policies_for(earlier["kind"]), earlier["kind"], "nudge")
        if now - earlier["base_at"] < timedelta(minutes=nudge):
            break
        run += 1
    return run


def is_silent(prompts: list[dict], policy: dict, now: datetime) -> tuple[bool, datetime]:
    """(silent?, since when): prompted for the whole window and not once answered. Returns the start of the window as the second value."""
    days = int(policy.get("silent_stop_days") or DEFAULTS["silent_stop_days"])
    window_start = now - timedelta(days=days)
    if not prompts or prompts[0]["base_at"] > window_start:
        return False, window_start      # not prompted for long enough yet to say anything
    answered = [p for p in prompts if p["responded"] and p["base_at"] >= window_start]
    return (not answered), window_start


# ---------------------------------------------------------------- the run
def run(db: Client, wa: Messenger, settings: Settings, now: datetime, in_scope: Callable[[str], bool], counts: Counter) -> None:
    policies = active_policies(db, settings, in_scope)
    if not policies:
        return
    parents = {
        r["id"]: r for r in db.table("care_recipients")
        .select("id, circle_id, language, name, call_name, whatsapp_phone, consent_at, opted_out_at, is_self").in_("id", sorted(policies)).execute().data or []
    }
    for recipient_id, policy in policies.items():
        parent = parents.get(recipient_id)
        if not parent or parent["is_self"]:
            continue                       # nobody nudges or alerts about the caregiver themselves
        try:
            _process_parent(db, wa, now, parent, policy, counts)
        except Exception as exc:  # noqa: BLE001  one parent's problem never stops the others
            log.error("escalation failed for parent %s (%s)", recipient_id, type(exc).__name__)
            counts["escalation_errors"] += 1


def _process_parent(db: Client, wa: Messenger, now: datetime, parent: dict, policy: dict, counts: Counter) -> None:
    rid = parent["id"]
    silent_days = int(policy.get("silent_stop_days") or DEFAULTS["silent_stop_days"])
    prompts = gather_prompts(db, rid, now - timedelta(days=silent_days + 1))
    if not prompts:
        return
    silent, window_start = is_silent(prompts, policy, now)
    existing = {
        r["slot_key"]: r for r in db.table("escalations").select("*").eq("recipient_id", rid).in_("slot_key", [p["key"] for p in prompts]).execute().data or []
    }
    def policy_for(_kind: str) -> dict:   # one policy per parent today; the per-kind numbers live inside it
        return policy

    for index, prompt in enumerate(prompts):
        kind = prompt["kind"]
        age = now - prompt["base_at"]
        nudge_min, alert_min = _minutes(policy, kind, "nudge"), _minutes(policy, kind, "alert")
        row = existing.get(prompt["key"])

        if prompt["responded"]:
            if row and row["resolved_at"] is None:
                _resolve_by_parent(db, parent, row, now, counts)
            if kind == "dose" and age >= timedelta(minutes=alert_min):
                _mark_missed(db, prompt, counts)          # the doses the parent did NOT confirm in a partly answered slot
            continue

        if age > ACTION_LOOKBACK:
            _mark_missed(db, prompt, counts)               # too old to act on, but the record must still say "missed"
            continue
        if row is None:
            row = _open(db, rid, prompt)
        if row is None:
            continue
        if row["resolved_at"] is not None:                 # the family already handled it: no nudge or alert, but the record still says "missed"
            if age >= timedelta(minutes=alert_min):
                _mark_missed(db, prompt, counts)
            continue

        if row["decided_at"] is None and age >= timedelta(minutes=alert_min):
            _decide(db, parent, policy, row, prompt, unanswered_run(prompts, index, policy_for, now), silent, now, counts)
        elif row["nudged_at"] is None and row["decided_at"] is None and age >= timedelta(minutes=nudge_min) and not silent:
            _nudge(db, wa, parent, row, prompt, now, counts)

    if silent:
        _tell_family_once(db, parent, policy, prompts, window_start, now, counts)


# ---------------------------------------------------------------- steps
def _open(db: Client, recipient_id: str, prompt: dict) -> dict | None:
    db.table("escalations").upsert(
        {"recipient_id": recipient_id, "kind": prompt["kind"], "slot_key": prompt["key"], "base_at": _iso(prompt["base_at"])},
        on_conflict="recipient_id,slot_key", ignore_duplicates=True,
    ).execute()
    rows = db.table("escalations").select("*").eq("recipient_id", recipient_id).eq("slot_key", prompt["key"]).execute().data
    return rows[0] if rows else None


def _mark_missed(db: Client, prompt: dict, counts: Counter) -> list[str]:
    """Doses (or the check-in) the parent never answered become 'missed'. Returns the ids that this call changed."""
    if prompt["kind"] == "dose":
        ids = [d["id"] for d in prompt["doses"] if d["status"] == "sent"]
        table = "dose_events"
    else:
        ids = [prompt["checkin_id"]] if prompt["status"] == "sent" else []
        table = "checkins"
    if not ids:
        return []
    changed = db.table(table).update({"status": "missed"}).in_("id", ids).eq("status", "sent").execute().data or []
    counts["doses_missed" if table == "dose_events" else "checkins_missed"] += len(changed)
    return [c["id"] for c in changed]


def _nudge(db: Client, wa: Messenger, parent: dict, row: dict, prompt: dict, now: datetime, counts: Counter) -> None:
    claimed = db.table("escalations").update({"nudged_at": _iso(now)}).eq("id", row["id"]).is_("nudged_at", "null").is_("resolved_at", "null").execute().data
    if not claimed:
        return                                              # another tick already took this step
    try:
        if prompt["kind"] == "dose":
            waiting = [d for d in prompt["doses"] if d["status"] == "sent" and not d["responded"]]
            result = messaging.send_dose_nudge(db, wa, parent, waiting)
        else:
            result = messaging.send_checkin_nudge(db, wa, parent, prompt["checkin_id"])
    except Exception as exc:  # noqa: BLE001
        db.table("escalations").update({"nudged_at": None}).eq("id", row["id"]).execute()   # hand it back: retried until the alert time
        counts["send_errors"] += 1
        log.error("nudge failed (%s)", type(exc).__name__)
        return
    if result == "sent":
        counts["nudges_sent"] += 1


def _decide(db: Client, parent: dict, policy: dict, row: dict, prompt: dict, run_length: int, silent: bool, now: datetime, counts: Counter) -> None:
    """The alert moment. The doses are marked missed either way (the record is true); the family hears about it only if it matters."""
    threshold = int(policy.get("alert_after_unanswered") or DEFAULTS["alert_after_unanswered"])
    alert = (not silent) and parent["opted_out_at"] is None and (run_length >= threshold or prompt["critical"])
    claimed = db.table("escalations").update(
        {"decided_at": _iso(now), "alerted_at": _iso(now) if alert else None}
    ).eq("id", row["id"]).is_("decided_at", "null").is_("resolved_at", "null").execute().data
    if not claimed:
        return                                              # another tick decided first
    _mark_missed(db, prompt, counts)
    if not alert:
        counts["alerts_suppressed"] += 1
        return
    if prompt["kind"] == "dose":
        unanswered = [d for d in prompt["doses"] if not d["responded"]]
        first = unanswered[0] if unanswered else prompt["doses"][0]
        payload = {"recipient_id": parent["id"], "medicine_id": first["medicine_id"], "dose_event_id": first["id"]}
        if len(unanswered) > 1:
            payload["count"] = len(unanswered)
        notify_circle(db, parent["circle_id"], "dose_missed", payload)
    else:
        notify_circle(db, parent["circle_id"], "checkin_missed", {"recipient_id": parent["id"], "checkin_id": prompt["checkin_id"]})
    counts["escalation_alerts"] += 1


def _resolve_by_parent(db: Client, parent: dict, row: dict, now: datetime, counts: Counter) -> None:
    claimed = db.table("escalations").update({"resolved_at": _iso(now), "resolved_by": "parent"}).eq("id", row["id"]).is_("resolved_at", "null").execute().data
    if claimed and row["alerted_at"]:
        notify_circle(db, parent["circle_id"], "parent_responded", {"recipient_id": parent["id"]})   # tell the people we had alerted
        counts["parent_responded"] += 1


def _tell_family_once(db: Client, parent: dict, policy: dict, prompts: list[dict], window_start: datetime, now: datetime, counts: Counter) -> None:
    """A parent who has answered nothing for days: stop the extras and say so ONCE (until they answer again)."""
    answered = [p["base_at"] for p in prompts if p["responded"]]
    since = max([window_start, *answered])
    seen = (
        db.table("notifications").select("id").eq("type", "parent_silent").eq("payload->>recipient_id", parent["id"])
        .gte("created_at", _iso(since)).limit(1).execute().data
    )
    if seen or parent["opted_out_at"] is not None:
        return
    notify_circle(db, parent["circle_id"], "parent_silent", {"recipient_id": parent["id"], "days": int(policy.get("silent_stop_days") or DEFAULTS["silent_stop_days"])})
    counts["parent_silent"] += 1
