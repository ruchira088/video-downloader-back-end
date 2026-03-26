from fastapi import FastAPI

from src.services.system_service import SystemService, SystemServiceImpl
from src.config.configuration import AppConfiguration
from src.services.user_service import get_user_service, UserService
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.schedule_router import schedule_router
from src.web.routers.service_router import service_router
from src.web.routers.user_router import user_router
from src.web.routers.video_router import video_router


def create_http_app(app_configuration: AppConfiguration) -> FastAPI:
    app = FastAPI()

    user_service: UserService = get_user_service(app_configuration)
    system_service: SystemService = SystemServiceImpl(app_configuration)

    app.include_router(user_router(user_service))
    app.include_router(schedule_router())
    app.include_router(video_router())
    app.include_router(service_router(system_service))

    register_exception_handlers(app)

    return app
