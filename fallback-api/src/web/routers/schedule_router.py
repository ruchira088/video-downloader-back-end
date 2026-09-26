from collections.abc import Callable

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from src.services.models.scheduled_video import ScheduleListing
from src.services.models.user import User
from src.services.scheduling_service import SchedulingService


class ScheduleVideoRequest(BaseModel):
    url: str


class ScheduleVideoResponse(BaseModel):
    requestId: str


def schedule_router(
    scheduling_service: SchedulingService, authenticated_user: Callable[..., User]
) -> APIRouter:
    router = APIRouter(prefix="/schedule")

    @router.post("", status_code=202, response_model=ScheduleVideoResponse)
    def schedule_video(
        schedule_video_request: ScheduleVideoRequest,
        user: User = Depends(authenticated_user),
    ):
        request_id = scheduling_service.schedule(schedule_video_request.url, user)
        return ScheduleVideoResponse(requestId=request_id)

    @router.get("", response_model=ScheduleListing)
    def list_schedules(
        status: str | None = None,
        pageToken: str | None = None,
        user: User = Depends(authenticated_user),
    ):
        return scheduling_service.list_schedules(user, status, pageToken)

    return router
