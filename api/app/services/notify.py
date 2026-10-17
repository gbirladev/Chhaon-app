from supabase import Client

# Things that only make sense when the person is somebody ELSE (a parent). For the caregiver's own profile ("Me", its own private circle)
# they would only tell you about yourself, so they are not created. Refill, out-of-stock, "reminder not sent" and "WhatsApp failed" stay.
SELF_QUIET = {
    "dose_missed", "checkin_missed", "checkin_call_requested", "parent_consented", "parent_declined", "parent_stopped", "parent_resumed",
    "parent_responded", "parent_silent", "appointment_reminder", "love_parent_opted_out", "love_parent_opted_in", "parent_message",
}


def notify_circle(db: Client, circle_id: str, type_: str, payload: dict | None = None) -> None:
    """In-app notification for every caregiver in the circle.
    The payload carries ids only: never health data or phone numbers."""
    if type_ in SELF_QUIET and db.table("care_recipients").select("id").eq("circle_id", circle_id).eq("is_self", True).limit(1).execute().data:
        return
    members = db.table("care_circle_members").select("user_id").eq("circle_id", circle_id).execute().data
    if members:
        db.table("notifications").insert(
            [{"user_id": m["user_id"], "type": type_, "payload": payload or {}} for m in members]
        ).execute()
