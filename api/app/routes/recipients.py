from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from supabase import Client

from app.config import Settings, get_settings
from app.deps import current_user_id, get_db, get_messenger
from app.services.messaging import send_consent_request
from app.whatsapp.client import Messenger, WhatsAppError

router = APIRouter(prefix="/recipients", tags=["recipients"])


def _require_member(db: Client, recipient_id: str, user_id: str) -> None:
    """The caller must belong to the circle that owns this parent."""
    r = db.table("care_recipients").select("circle_id").eq("id", recipient_id).maybe_single().execute()
    if not r or not r.data:
        raise HTTPException(status_code=404, detail="Not found")
    member = (
        db.table("care_circle_members").select("user_id")
        .eq("circle_id", r.data["circle_id"]).eq("user_id", user_id).execute().data
    )
    if not member:
        raise HTTPException(status_code=404, detail="Not found")  # same answer: do not reveal other circles


@router.post("/{recipient_id}/consent-request")
def request_consent(
    recipient_id: UUID,
    user_id: str = Depends(current_user_id),
    db: Client = Depends(get_db),
    wa: Messenger = Depends(get_messenger),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    """Asks the parent, on WhatsApp, to agree to receive Chhaon messages."""
    _require_member(db, str(recipient_id), user_id)
    try:
        status = send_consent_request(db, wa, settings, str(recipient_id), user_id)
    except WhatsAppError as exc:
        raise HTTPException(status_code=502, detail="WhatsApp is unavailable. Try again shortly.") from exc
    return {"status": status}
