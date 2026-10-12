from supabase import Client


def notify_circle(db: Client, circle_id: str, type_: str, payload: dict | None = None) -> None:
    """In-app notification for every caregiver in the circle.
    The payload carries ids only: never health data or phone numbers."""
    members = db.table("care_circle_members").select("user_id").eq("circle_id", circle_id).execute().data
    if members:
        db.table("notifications").insert(
            [{"user_id": m["user_id"], "type": type_, "payload": payload or {}} for m in members]
        ).execute()
