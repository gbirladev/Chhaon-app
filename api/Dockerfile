# Chhaon API (FastAPI). Build: docker build -t chhaon-api api
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 UV_LINK_MODE=copy
RUN pip install --no-cache-dir uv==0.9.*

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app ./app

RUN useradd --create-home appuser
USER appuser

# The host sets $PORT. No access log: the public emergency link has a secret in its path (see docs/deploy.md).
CMD ["sh", "-c", "exec /app/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --no-access-log"]
