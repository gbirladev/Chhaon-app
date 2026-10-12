import hashlib
import hmac


def verify_signature(raw_body: bytes, header: str | None, app_secret: str) -> bool:
    """Meta signs the exact raw request body: X-Hub-Signature-256 = 'sha256=' + HMAC-SHA256(app_secret, body)."""
    if not app_secret or not header or not header.startswith("sha256="):
        return False
    expected = hmac.new(app_secret.encode(), raw_body, hashlib.sha256).hexdigest()
    # compare bytes: hmac.compare_digest raises TypeError on non-ASCII str, which would be a 500 for the caller
    return hmac.compare_digest(expected.encode(), header.removeprefix("sha256=").encode())


def sign(raw_body: bytes, app_secret: str) -> str:
    """Used by tests and the local simulator to produce a valid header."""
    return "sha256=" + hmac.new(app_secret.encode(), raw_body, hashlib.sha256).hexdigest()
