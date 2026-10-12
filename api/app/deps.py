from fastapi import Depends, Header, HTTPException, Request
from supabase import Client

from app.config import Settings, get_settings
from app.db import service_client
from app.services.reports import AnthropicExtractor, Extractor
from app.whatsapp.client import Messenger, WhatsAppClient


def get_db() -> Client:
    return service_client()


MAX_WEBHOOK_BYTES = 1_000_000  # real Meta webhooks are a few KB
_messenger: WhatsAppClient | None = None


def get_messenger(settings: Settings = Depends(get_settings)) -> Messenger:
    """One shared client (keeps the HTTP connection pool). Settings are not hashable, so no lru_cache here."""
    global _messenger
    if _messenger is None:
        _messenger = WhatsAppClient(settings)
    return _messenger


_extractor: AnthropicExtractor | None = None


def get_extractor(settings: Settings = Depends(get_settings)) -> Extractor:
    """One shared Anthropic client. Built lazily so the app starts without an API key."""
    global _extractor
    if _extractor is None:
        _extractor = AnthropicExtractor(settings)
    return _extractor


def current_user_id(authorization: str | None = Header(default=None), db: Client = Depends(get_db)) -> str:
    """Validates the caregiver's Supabase access token (sent by the web app) and returns their user id."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Missing token")
    try:
        user = db.auth.get_user(authorization.split(" ", 1)[1])
    except Exception as exc:  # any failure to validate means "not logged in"
        raise HTTPException(status_code=401, detail="Invalid token") from exc
    if not user or not user.user:
        raise HTTPException(status_code=401, detail="Invalid token")
    return user.user.id


async def raw_body(request: Request) -> bytes:
    """The exact bytes Meta signed. Must not be re-serialised before verifying."""
    body = await request.body()
    if len(body) > MAX_WEBHOOK_BYTES:
        raise HTTPException(status_code=413, detail="Payload too large")
    return body
