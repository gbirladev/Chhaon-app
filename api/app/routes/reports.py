import logging
import time
from collections import defaultdict, deque
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from supabase import Client

from app.config import Settings, get_settings
from app.deps import current_user_id, get_db, get_extractor
from app.routes.recipients import _require_member
from app.services.reports import (
    ExtractionError,
    Extractor,
    media_type_for,
    parse_extraction,
    store_result,
)

router = APIRouter(prefix="/reports", tags=["reports"])
log = logging.getLogger(__name__)

_recent: dict[str, deque[float]] = defaultdict(deque)  # per caregiver, in this process


class AnalyzeRequest(BaseModel):
    language: Literal["en", "hi"] = "en"


def _rate_limited(user_id: str, limit: int) -> bool:
    now = time.monotonic()
    q = _recent[user_id]
    while q and now - q[0] > 3600:
        q.popleft()
    if len(q) >= limit:
        return True
    q.append(now)
    return False


def _set_status(db: Client, report_id: str, status: str) -> None:
    db.table("reports").update({"ai_status": status}).eq("id", report_id).execute()


@router.post("/{report_id}/analyze")
def analyze_report(
    report_id: UUID,
    body: AnalyzeRequest,
    user_id: str = Depends(current_user_id),
    db: Client = Depends(get_db),
    extractor: Extractor = Depends(get_extractor),
    settings: Settings = Depends(get_settings),
) -> dict:
    rid = str(report_id)
    rep = db.table("reports").select("id,recipient_id,file_url,ai_status").eq("id", rid).maybe_single().execute()
    if not rep or not rep.data:
        raise HTTPException(status_code=404, detail="Not found")
    report = rep.data
    _require_member(db, report["recipient_id"], user_id)

    if report["ai_status"] == "processing":
        return {"status": "processing"}
    if report["ai_status"] == "done":
        n = db.table("report_values").select("id", count="exact").eq("report_id", rid).execute().count or 0
        return {"status": "done", "values": n}
    if _rate_limited(user_id, settings.report_analyses_per_hour):
        raise HTTPException(status_code=429, detail="Too many reports. Please try again later.")

    # Claim atomically: only one request can move pending/failed -> processing.
    claimed = db.table("reports").update({"ai_status": "processing"}).eq("id", rid).in_("ai_status", ["pending", "failed"]).execute().data
    if not claimed:
        return {"status": "processing"}

    try:
        if not settings.anthropic_api_key:
            _set_status(db, rid, "failed")
            raise HTTPException(status_code=503, detail="AI reading is not set up yet")
        path = report["file_url"] or ""
        media_type = media_type_for(path)
        if not path.startswith(f"{report['recipient_id']}/") or ".." in path or media_type is None:
            log.warning("report %s: refused storage path", rid)
            _set_status(db, rid, "failed")
            raise HTTPException(status_code=400, detail="Invalid file")
        data = db.storage.from_("reports").download(path)
        if not data or len(data) > settings.report_max_bytes:
            raise ExtractionError("unreadable or too large")
        ex = parse_extraction(extractor.extract(data, media_type, body.language))
        n = store_result(db, report, ex, body.language)
        log.info("report %s analyzed", rid)
        return {"status": "done", "values": n}
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001 model, network, storage or validation failure: never leave it stuck
        log.warning("report %s failed: %s", rid, type(exc).__name__)
        _set_status(db, rid, "failed")
        return {"status": "failed"}
    finally:
        cur = db.table("reports").select("ai_status").eq("id", rid).maybe_single().execute()
        if cur and cur.data and cur.data["ai_status"] == "processing":
            _set_status(db, rid, "failed")
