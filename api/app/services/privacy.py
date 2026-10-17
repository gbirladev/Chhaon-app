"""Data export and erasure for a caregiver (DPDP Act 2023: access, portability, erasure).

Everything here runs with the service role, so each function takes the caller's id and only ever
reaches rows through THEIR circles. Never log health data or phone numbers."""

import logging
from datetime import UTC, datetime

from supabase import Client

log = logging.getLogger("carelink.privacy")

PAGE = 1000
CHUNK = 50
BUCKETS = ("medicine-photos", "reports")
# Columns that are secrets or internal plumbing, not the user's data.
HIDDEN = {"token", "whatsapp_message_id"}


def _chunks(items: list[str], size: int = CHUNK):
    for i in range(0, len(items), size):
        yield items[i : i + size]


def _clean(row: dict) -> dict:
    return {k: v for k, v in row.items() if k not in HIDDEN}


def _fetch(db: Client, table: str, column: str, ids: list[str], order: str | None = None) -> list[dict]:
    """All rows where `column` is in `ids` (paged, so a long dose history is never cut off)."""
    rows: list[dict] = []
    for part in _chunks(ids):
        start = 0
        while True:
            q = db.table(table).select("*").in_(column, part)
            if order:
                q = q.order(order)
            batch = q.range(start, start + PAGE - 1).execute().data or []
            rows += batch
            if len(batch) < PAGE:
                break
            start += PAGE
    return [_clean(r) for r in rows]


def build_export(db: Client, user_id: str) -> dict:
    profile = db.table("profiles").select("*").eq("id", user_id).maybe_single().execute()
    memberships = db.table("care_circle_members").select("*").eq("user_id", user_id).execute().data or []
    circle_ids = [m["circle_id"] for m in memberships]

    circles = _fetch(db, "care_circles", "id", circle_ids, "created_at")
    members = _fetch(db, "care_circle_members", "circle_id", circle_ids, "created_at")
    names = {}
    for part in _chunks(sorted({m["user_id"] for m in members})):
        for p in db.table("profiles").select("id, name").in_("id", part).execute().data or []:
            names[p["id"]] = p["name"]
    members = [{"circle_id": m["circle_id"], "role": m["role"], "name": names.get(m["user_id"]), "is_you": m["user_id"] == user_id, "joined_at": m["created_at"]} for m in members]

    recipients = _fetch(db, "care_recipients", "circle_id", circle_ids, "created_at")
    rids = [r["id"] for r in recipients]
    medicines = _fetch(db, "medicines", "recipient_id", rids, "created_at")
    mids = [m["id"] for m in medicines]
    schedules = _fetch(db, "medicine_schedules", "medicine_id", mids, "created_at")
    sids = [s["id"] for s in schedules]
    doses = _fetch(db, "dose_events", "schedule_id", sids, "scheduled_at")
    checkins = _fetch(db, "checkins", "recipient_id", rids, "date")
    documents = _fetch(db, "documents", "recipient_id", rids, "created_at")
    document_files = _fetch(db, "document_files", "document_id", [d["id"] for d in documents])
    reports = _fetch(db, "reports", "recipient_id", rids, "created_at")
    values = _fetch(db, "report_values", "report_id", [r["id"] for r in reports])
    appointments = _fetch(db, "appointments", "recipient_id", rids, "datetime")
    links = [{"recipient_id": e["recipient_id"], "active": e["active"], "created_at": e["created_at"]}
             for e in _fetch(db, "emergency_links", "recipient_id", rids, "created_at")]  # the secret link itself is never exported
    notifications = _fetch(db, "notifications", "user_id", [user_id], "created_at")

    return {
        "exported_at": datetime.now(UTC).isoformat(),
        "about": "Everything Chhaon holds that you can see. Uploaded files (wallet documents, report photos/PDFs, medicine photos) are not included here; "
                 "their storage paths are. Secret links and sign-in tokens are never exported.",
        "profile": _clean(profile.data) if profile and profile.data else None,
        "circles": circles,
        "circle_members": members,
        "parents": recipients,
        "medicines": medicines,
        "medicine_schedules": schedules,
        "dose_events": doses,
        "checkins": checkins,
        "documents": documents,
        "document_files": document_files,
        "reports": reports,
        "report_values": values,
        "appointments": appointments,
        "emergency_links": links,
        "notifications": notifications,
    }


MAX_DEPTH = 6


def _purge_folder(db: Client, bucket: str, folder: str, depth: int = 0) -> None:
    """Removes every file under `folder`, including nested sub-folders (storage lists a folder as an entry without an id)."""
    store = db.storage.from_(bucket)
    entries: list[dict] = []
    offset = 0
    while True:  # page through everything first: removing while paging would shift the offsets
        page = store.list(folder, {"limit": 100, "offset": offset}) or []
        entries += [e for e in page if e.get("name")]
        if len(page) < 100:
            break
        offset += 100
    files = [f"{folder}/{e['name']}" for e in entries if e.get("id")]
    for part in _chunks(files, 100):
        store.remove(part)
    if depth < MAX_DEPTH:
        for e in entries:
            if not e.get("id"):
                _purge_folder(db, bucket, f"{folder}/{e['name']}", depth + 1)


def purge_recipient_files(db: Client, recipient_ids: list[str]) -> None:
    """Deletes every stored file of these parents. Raises if storage fails, so the caller can stop before deleting records."""
    for rid in recipient_ids:
        for bucket in BUCKETS:
            _purge_folder(db, bucket, rid)


def delete_recipient(db: Client, user_id: str, recipient_id: str) -> bool:
    """Owner-only: erases one parent (files first, then the records). False when not found / not the owner."""
    r = db.table("care_recipients").select("circle_id").eq("id", recipient_id).maybe_single().execute()
    if not r or not r.data:
        return False
    owner = (db.table("care_circle_members").select("user_id").eq("circle_id", r.data["circle_id"])
             .eq("user_id", user_id).eq("role", "owner").execute().data)
    if not owner:
        return False
    purge_recipient_files(db, [recipient_id])
    db.table("care_recipients").delete().eq("id", recipient_id).execute()
    log.info("parent record erased (recipient %s)", recipient_id)
    return True


def delete_account(db: Client, user_id: str) -> None:
    """Order matters: files -> records -> login. Each step is safe to repeat, so a failure can simply be retried."""
    sole = db.rpc("sole_member_recipients", {"p_user": user_id}).execute().data or []
    purge_recipient_files(db, [row["recipient_id"] if isinstance(row, dict) else row for row in sole])
    # Feedback messages stay (anonymous), but their screenshots may show personal or medical details: they go with the account.
    db.table("feedback").update({"images": []}).eq("user_id", user_id).execute()
    _purge_folder(db, "feedback", user_id)
    db.rpc("erase_caregiver", {"p_user": user_id}).execute()
    try:
        db.auth.admin.delete_user(user_id)
    except Exception as exc:
        if "not found" not in str(exc).lower():
            raise
    log.info("account erased (user %s)", user_id)
