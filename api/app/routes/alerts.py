import re

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from supabase import Client

from app.config import Settings, get_settings
from app.deps import current_user_id, get_db, get_messenger
from app.services.alerts import request_opt_in
from app.whatsapp.client import Messenger, WhatsAppError

router = APIRouter(prefix="/alerts/whatsapp", tags=["alerts"])
E164 = re.compile(r"^\+[1-9]\d{7,14}$")


class OptInRequest(BaseModel):
    phone: str


@router.post("/request")
def request_alerts(
    body: OptInRequest,
    user_id: str = Depends(current_user_id),
    db: Client = Depends(get_db),
    wa: Messenger = Depends(get_messenger),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    """Sends the caregiver a WhatsApp message asking them to confirm alerts. Alerts start only after they tap Yes."""
    phone = body.phone.strip()
    if not E164.match(phone):
        raise HTTPException(status_code=422, detail="Invalid phone number")
    try:
        return {"status": request_opt_in(db, wa, settings, user_id, phone)}
    except WhatsAppError as exc:
        raise HTTPException(status_code=502, detail="WhatsApp is unavailable. Try again shortly.") from exc
