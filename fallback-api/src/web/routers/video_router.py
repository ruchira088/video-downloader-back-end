from fastapi import APIRouter


def video_router() -> APIRouter:
    router = APIRouter(prefix="/video")
    return router
