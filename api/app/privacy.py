"""Helpers that keep health data and phone numbers out of logs."""


def mask_phone(phone: str | None) -> str:
    """'+919810012345' -> '+91••••••••45'. Never log a full number."""
    if not phone:
        return "<none>"
    digits = phone.strip()
    return f"{digits[:3]}{'•' * max(len(digits) - 5, 0)}{digits[-2:]}"
