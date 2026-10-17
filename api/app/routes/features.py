from fastapi import APIRouter, Depends

from app.config import Settings, get_settings

router = APIRouter(tags=["features"])


@router.get("/features")
def features(settings: Settings = Depends(get_settings)) -> dict[str, bool]:
    """Which optional features this server has switched on, so the app only shows screens that really work. No login, no secrets."""
    return {"escalation": settings.escalation_enabled, "love": settings.love_loop_enabled}
