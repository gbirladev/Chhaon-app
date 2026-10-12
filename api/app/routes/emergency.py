"""Public (no login) emergency card. Anyone holding the link may read it, so it returns the bare minimum a paramedic
needs and nothing else: no address/city, no date of birth, no stock levels, no reports, no WhatsApp number of the parent
unless the caregiver listed it as an emergency contact. Never log the token or any of the data."""
import logging
import re
import threading
import time
from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from supabase import Client

from app.config import Settings, get_settings
from app.deps import get_db

router = APIRouter(prefix="/public/emergency", tags=["emergency"])
log = logging.getLogger("carelink.emergency")

TOKEN = re.compile(r"^[0-9a-f]{64}$")
E164 = re.compile(r"^\+[1-9]\d{7,14}$")
DUMMY_TOKEN = "0" * 64
NO_STORE = {"Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow", "Referrer-Policy": "no-referrer"}

RATE_LIMIT = 30  # requests per IP per minute: enough for a family, far too few to guess a 256-bit token
RATE_WINDOW = 60.0
MAX_TRACKED_IPS = 10_000
_hits: dict[str, list[float]] = {}
_lock = threading.Lock()


def reset_rate_limit() -> None:
    with _lock:
        _hits.clear()


def client_ip(request: Request, trusted_hops: int = 0) -> str:
    """The address to rate-limit. By default ONLY the socket peer as the server reports it (`request.client.host`);
    X-Forwarded-For is never read unless `trusted_proxy_hops` > 0, and then only the entry added by our own
    outermost trusted proxy (counted from the right), because anything to its left is client-supplied."""
    peer = request.client.host if request.client else "unknown"
    if trusted_hops > 0:
        parts = [p.strip() for p in request.headers.get("x-forwarded-for", "").split(",") if p.strip()]
        if len(parts) >= trusted_hops:
            return parts[-trusted_hops][:64]
    return peer


def _limited(ip: str) -> bool:
    now = time.monotonic()
    with _lock:
        if len(_hits) >= MAX_TRACKED_IPS:  # never grow without bound: drop only entries that have expired
            for key in [k for k, v in _hits.items() if not v or now - v[-1] >= RATE_WINDOW]:
                del _hits[key]
            if len(_hits) >= MAX_TRACKED_IPS and ip not in _hits:
                return True  # table full of live entries: refuse newcomers rather than forget attackers
        recent = [t for t in _hits.get(ip, []) if now - t < RATE_WINDOW]
        recent.append(now)
        _hits[ip] = recent
        return len(recent) > RATE_LIMIT


def _not_found() -> JSONResponse:
    """Identical for unknown, revoked, malformed and deleted: nothing to learn from the answer."""
    return JSONResponse({"detail": "Not found"}, status_code=404, headers=NO_STORE)


# Invisible characters that can reorder or hide text on a screen: bidi controls, zero-width space, BOM (ZWJ/ZWNJ stay: Hindi needs them).
INVISIBLE = re.compile("[\u202a-\u202e\u2066-\u2069\u200e\u200f\u061c\u200b\u2060\ufeff\u00ad]")
CONTROL = re.compile("[\x00-\x1f\x7f\u2028\u2029]+")


def _clean(value: object, limit: int = 120) -> str:
    return CONTROL.sub(" ", INVISIBLE.sub("", value)).strip()[:limit] if isinstance(value, str) else ""


def _age(row: dict) -> int | None:
    if isinstance(row.get("age"), int):
        return row["age"]
    if row.get("dob"):
        try:
            born = date.fromisoformat(row["dob"])
        except ValueError:
            return None
        today = datetime.now(UTC).date()
        return today.year - born.year - ((today.month, today.day) < (born.month, born.day))
    return None


def _contacts(row: dict, members: list[dict]) -> list[dict]:
    out: list[dict] = []
    raw = row.get("emergency_contacts")
    for c in raw if isinstance(raw, list) else []:
        if isinstance(c, dict) and E164.match(_clean(c.get("phone"), 20)):
            out.append({"name": _clean(c.get("name")), "phone": _clean(c.get("phone"), 20)})
    for m in members:  # the same family contacts the in-app Emergency card shows (caregivers with a phone)
        profile = m.get("profiles") or {}
        phone = _clean(profile.get("phone"), 20)
        if E164.match(phone) and not any(c["phone"] == phone for c in out):
            out.append({"name": _clean(profile.get("name")), "phone": phone})
    return out[:4]


def _doctor(row: dict) -> str | None:
    raw = row.get("doctor_contacts")
    for d in raw if isinstance(raw, list) else []:
        name = _clean(d.get("name") if isinstance(d, dict) else d)
        if name:
            return name
    return None


def build_card(db: Client, link: dict) -> dict | None:
    rec = db.table("care_recipients").select(
        "name, age, dob, blood_group, conditions, allergies, doctor_contacts, emergency_contacts, circle_id"
    ).eq("id", link["recipient_id"]).limit(1).execute().data
    if not rec:
        return None
    row = rec[0]
    meds = db.table("medicines").select("name, dosage, medicine_schedules(time_of_day, end_date)").eq(
        "recipient_id", link["recipient_id"]).eq("active", True).order("created_at").execute().data
    today = datetime.now(UTC).date().isoformat()
    medicines = []
    for m in meds:
        times = sorted({s["time_of_day"][:5] for s in m.get("medicine_schedules") or [] if not s.get("end_date") or s["end_date"] >= today})
        medicines.append({"name": _clean(m["name"]), "dose": _clean(m.get("dosage")), "times": times})
    members = db.table("care_circle_members").select("profiles(name, phone)").eq("circle_id", row["circle_id"]).order("created_at").execute().data
    return {
        "name": (_clean(row["name"]).split() or [""])[0],
        "age": _age(row),
        "blood_group": _clean(row.get("blood_group"), 5) or None,
        "conditions": [_clean(c) for c in row.get("conditions") or []][:30],
        "allergies": [_clean(a) for a in row.get("allergies") or []][:30],
        "medicines": medicines[:40],
        "contacts": _contacts(row, members),
        "doctor": _doctor(row),
    }


@router.get("/{token:path}")
def public_card(token: str, request: Request, db: Client = Depends(get_db), settings: Settings = Depends(get_settings)):
    if _limited(client_ip(request, settings.trusted_proxy_hops)):
        return JSONResponse({"detail": "Too many requests"}, status_code=429, headers={**NO_STORE, "Retry-After": "60"})
    token = token.strip().lower()
    valid = bool(TOKEN.match(token))  # anything else (also path-like text such as ../x) gets the same 404 as an unknown token
    rows = db.table("emergency_links").select("id, recipient_id").eq("token", token if valid else DUMMY_TOKEN).eq("active", True).limit(1).execute().data
    if not valid or not rows:
        return _not_found()
    card = build_card(db, rows[0])
    if card is None:
        return _not_found()
    db.rpc("record_emergency_view", {"p_link": rows[0]["id"]}).execute()  # atomic: view_count = view_count + 1
    log.info("emergency card viewed")
    return JSONResponse(card, headers=NO_STORE)
