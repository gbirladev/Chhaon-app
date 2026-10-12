from functools import lru_cache

from supabase import Client, create_client

from app.config import get_settings


@lru_cache
def service_client() -> Client:
    """Service-role client (bypasses RLS). Use only for system work: webhook, scheduler, consent."""
    s = get_settings()
    return create_client(s.supabase_url, s.supabase_service_role_key)
