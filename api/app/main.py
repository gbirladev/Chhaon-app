from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routes import alerts, cron, emergency, privacy, recipients, reports, webhooks

settings = get_settings()
app = FastAPI(title="Chhaon API", docs_url="/docs" if settings.environment != "production" else None)

# In development the web app may be opened from a phone on the local network.
LOCAL_NETWORK = r"http://(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+):\d+"
app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.web_origin],
    allow_origin_regex=LOCAL_NETWORK if settings.environment == "development" else None,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)

app.include_router(webhooks.router)
app.include_router(recipients.router)
app.include_router(cron.router)
app.include_router(alerts.router)
app.include_router(reports.router)
app.include_router(emergency.router)
app.include_router(privacy.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
