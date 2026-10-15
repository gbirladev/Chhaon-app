from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "development"
    supabase_url: str = "http://127.0.0.1:54321"
    # Service role key bypasses RLS. Backend only; never expose to the browser.
    supabase_service_role_key: str = ""
    web_origin: str = "http://localhost:5173"

    # WhatsApp Business Cloud API (Meta). Without an access token the client runs in dry-run mode:
    # nothing is sent, messages are only logged (phone numbers masked).
    whatsapp_access_token: str = ""
    whatsapp_phone_number_id: str = ""
    whatsapp_app_secret: str = ""  # verifies X-Hub-Signature-256 on webhooks
    whatsapp_verify_token: str = ""  # shared secret for the webhook GET handshake
    whatsapp_api_version: str = "v21.0"
    # How long to wait before the same consent request may be re-sent to a parent.
    consent_resend_minutes: int = 10
    consent_requests_per_day: int = 10  # per caregiver

    # Scheduler windows (minutes). CLAUDE.md: ~60 min for a dose, ~3 hours for the daily check-in.
    dose_missed_minutes: int = 60
    checkin_missed_minutes: int = 180
    checkin_start_window_minutes: int = 120  # if the scheduler was down, do not send a "good morning" at 3 pm

    # One reminder per time slot: all medicines due at the same moment go in ONE message (needs the template
    # carelink_dose_reminder_multi approved by Meta). Off until then: every dose is reminded on its own.
    slot_reminders_enabled: bool = False

    # Escalation ladder (nudge the parent, then alert the family only when it matters). Off until switched on here AND per parent.
    escalation_enabled: bool = False

    # WhatsApp alerts to the caregiver
    alerts_per_hour: int = 6
    alerts_max_attempts: int = 3
    alerts_lookback_hours: int = 6  # older notifications are never sent late

    anthropic_api_key: str = ""
    report_model: str = "claude-sonnet-5-5"
    report_max_bytes: int = 15 * 1024 * 1024
    report_analyses_per_hour: int = 20  # per caregiver (in-process counter)
    cron_secret: str = ""
    # How many reverse proxies we run in front of the API. 0 = use the socket peer (never trust X-Forwarded-For).
    # With N > 0 the Nth address from the RIGHT of X-Forwarded-For is the client. See docs/deploy.md.
    trusted_proxy_hops: int = 0

    @property
    def whatsapp_dry_run(self) -> bool:
        return not (self.whatsapp_access_token and self.whatsapp_phone_number_id)


@lru_cache
def get_settings() -> Settings:
    return Settings()
