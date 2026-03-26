from fastapi import APIRouter, Depends

from src.services.models.user import User
from src.web.decorators.authentication import get_authenticated_user


def schedule_router() -> APIRouter:
    router = APIRouter(prefix="/schedule")

    @router.post("/")
    def schedule_video_download(user: User = Depends(get_authenticated_user)):
        pass

    return router
