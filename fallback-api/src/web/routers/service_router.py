from datetime import datetime

from fastapi import APIRouter


def service_router() -> APIRouter:
    router = APIRouter(prefix="/service")

    @router.get("/info")
    def info():
        return {
            "service_name": "video-downloader-fallback-api",
            "timestamp": datetime.now().isoformat(),
        }

    return router
