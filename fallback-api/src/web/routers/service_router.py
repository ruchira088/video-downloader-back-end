from fastapi import APIRouter

from src.services.system_service import SystemService, SystemInfo


def service_router(system_service: SystemService) -> APIRouter:
    router = APIRouter(prefix="/service")

    @router.get("/info", response_model=SystemInfo)
    def info():
        return system_service.get_system_info()

    return router
