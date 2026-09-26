from collections.abc import Callable

from fastapi import APIRouter, Depends

from src.services.models.user import User


def schedule_router(authenticated_user: Callable[..., User]) -> APIRouter:
    router = APIRouter(prefix="/schedule")

    @router.post("/")
    def schedule_video_download(user: User = Depends(authenticated_user)):
        pass

    return router
