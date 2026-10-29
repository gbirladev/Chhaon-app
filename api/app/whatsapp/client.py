import logging
from typing import Protocol
from uuid import uuid4

import httpx

from app.config import Settings
from app.privacy import mask_phone
from app.whatsapp.templates import Template

log = logging.getLogger("carelink.whatsapp")


class WhatsAppError(Exception):
    """Meta rejected the request or was unreachable. `code` is Meta's numeric error code when it gave one (never message content)."""

    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code


class Messenger(Protocol):
    def send_template(self, to: str, template: Template) -> str: ...
    def send_text(self, to: str, body: str) -> str: ...


class WhatsAppClient:
    """Thin wrapper over the WhatsApp Business Cloud API. Returns the message id (wamid)."""

    def __init__(self, settings: Settings, http: httpx.Client | None = None):
        self._settings = settings
        self._http = http or httpx.Client(timeout=15)
        self._url = (
            f"https://graph.facebook.com/{settings.whatsapp_api_version}/{settings.whatsapp_phone_number_id}/messages"
        )

    @staticmethod
    def _digits(e164: str) -> str:
        return e164.lstrip("+")  # Cloud API wants the number without '+'

    def _post(self, payload: dict) -> str:
        if self._settings.whatsapp_dry_run:
            # Never log bodies or full numbers: they can contain health information.
            log.info("DRY RUN whatsapp %s to %s", payload.get("type"), mask_phone("+" + payload["to"]))
            return f"dryrun-{uuid4()}"
        try:
            response = self._http.post(
                self._url,
                json=payload,
                headers={"Authorization": f"Bearer {self._settings.whatsapp_access_token}"},
            )
        except httpx.HTTPError as exc:
            raise WhatsAppError("WhatsApp API unreachable") from exc
        if response.status_code >= 400:
            # Meta's error body can echo message content; log only the status and error code.
            try:
                code = response.json().get("error", {}).get("code")
            except (ValueError, AttributeError):
                code = None
            code = code if isinstance(code, int) else None
            template = payload.get("template") or {}
            log.warning(
                "WhatsApp API error status=%s code=%s template=%s lang=%s",
                response.status_code, code, template.get("name"), (template.get("language") or {}).get("code"),
            )
            raise WhatsAppError(f"WhatsApp API error {response.status_code}", code=code)
        try:
            message = response.json()["messages"][0]
            # "held_for_quality_assessment" means Meta accepted it but delays delivery (new templates, pacing).
            if message.get("message_status") not in (None, "accepted"):
                log.warning("WhatsApp message_status=%s", message.get("message_status"))
            return message["id"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise WhatsAppError("WhatsApp API returned an unexpected response") from exc

    def send_template(self, to: str, template: Template) -> str:
        return self._post({
            "messaging_product": "whatsapp",
            "to": self._digits(to),
            "type": "template",
            "template": {
                "name": template.name,
                "language": {"code": template.language},
                "components": template.components,
            },
        })

    def send_text(self, to: str, body: str) -> str:
        return self._post({
            "messaging_product": "whatsapp",
            "to": self._digits(to),
            "type": "text",
            "text": {"body": body},
        })
