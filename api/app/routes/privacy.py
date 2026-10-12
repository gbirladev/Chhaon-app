import json
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
from supabase import Client

from app.deps import current_user_id, get_db
from app.services import privacy as service

router = APIRouter(prefix="/privacy", tags=["privacy"])


class DeleteAccount(BaseModel):
    confirm: str


@router.get("/export")
def export_my_data(user_id: str = Depends(current_user_id), db: Client = Depends(get_db)) -> Response:
    """A JSON file with everything the caller can access. Sent as a download, never cached."""
    body = json.dumps(service.build_export(db, user_id), ensure_ascii=False, indent=2, default=str)
    return Response(
        body,
        media_type="application/json",
        headers={"Content-Disposition": 'attachment; filename="carelink-my-data.json"', "Cache-Control": "no-store"},
    )


@router.post("/delete-account")
def delete_my_account(body: DeleteAccount, user_id: str = Depends(current_user_id), db: Client = Depends(get_db)) -> dict[str, str]:
    """Erases the caller. The confirmation word is checked here too, so a stray request cannot delete anything."""
    if body.confirm.strip().upper() != "DELETE":
        raise HTTPException(status_code=422, detail="Type DELETE to confirm")
    try:
        service.delete_account(db, user_id)
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Could not finish. Please try again.") from exc
    return {"status": "deleted"}


@router.post("/recipients/{recipient_id}/delete")
def delete_parent_data(recipient_id: UUID, user_id: str = Depends(current_user_id), db: Client = Depends(get_db)) -> dict[str, str]:
    """Owner only: removes one parent with everything attached to them, including stored files."""
    try:
        done = service.delete_recipient(db, user_id, str(recipient_id))
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Could not finish. Please try again.") from exc
    if not done:
        raise HTTPException(status_code=404, detail="Not found")  # same answer for "not yours" and "not there"
    return {"status": "deleted"}
