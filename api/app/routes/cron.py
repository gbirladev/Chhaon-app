import hmac

from fastapi import APIRouter, Depends, Header, HTTPException
from supabase import Client

from app.config import Settings, get_settings
from app.deps import get_db, get_messenger
from app.services.scheduler import run_tick
from app.whatsapp.client import Messenger

router = APIRouter(prefix="/cron", tags=["cron"])


@router.post("/tick")
def tick(
    x_cron_secret: str | None = Header(default=None),
    db: Client = Depends(get_db),
    wa: Messenger = Depends(get_messenger),
    settings: Settings = Depends(get_settings),
) -> dict[str, int]:
    """Called every 5 minutes by a cron service (see docs/scheduler.md). Protected by CRON_SECRET."""
    if not settings.cron_secret:
        raise HTTPException(status_code=503, detail="Scheduler not configured")
    if not x_cron_secret or not hmac.compare_digest(x_cron_secret.encode(), settings.cron_secret.encode()):
        raise HTTPException(status_code=403, detail="Forbidden")
    return run_tick(db, wa, settings)
