import hmac
import json
import logging

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse
from supabase import Client

from app.config import Settings, get_settings
from app.deps import get_db, get_messenger, raw_body
from app.services.inbound import process_webhook
from app.whatsapp.client import Messenger
from app.whatsapp.security import verify_signature

router = APIRouter(prefix="/webhooks/whatsapp", tags=["whatsapp"])
log = logging.getLogger("carelink.webhook")


@router.get("", response_class=PlainTextResponse)
def verify(
    mode: str = Query(alias="hub.mode", default=""),
    token: str = Query(alias="hub.verify_token", default=""),
    challenge: str = Query(alias="hub.challenge", default=""),
    settings: Settings = Depends(get_settings),
) -> str:
    """Meta's one-time handshake when the webhook URL is registered."""
    expected = settings.whatsapp_verify_token
    if mode == "subscribe" and expected and hmac.compare_digest(token.encode(), expected.encode()):
        return challenge
    raise HTTPException(status_code=403, detail="Verification failed")


@router.post("")
def receive(
    request: Request,
    body: bytes = Depends(raw_body),
    db: Client = Depends(get_db),
    wa: Messenger = Depends(get_messenger),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    if not settings.whatsapp_app_secret:
        raise HTTPException(status_code=503, detail="Webhook not configured")
    if not verify_signature(body, request.headers.get("X-Hub-Signature-256"), settings.whatsapp_app_secret):
        log.warning("rejected webhook with bad signature")
        raise HTTPException(status_code=403, detail="Bad signature")
    try:
        payload = json.loads(body)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="Invalid JSON") from exc
    process_webhook(db, wa, payload)  # raises -> 500 -> Meta retries (event ids are released first)
    return {"status": "ok"}
