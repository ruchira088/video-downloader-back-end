from fastapi import FastAPI
from pyparsing import ParseResults

from src.config.Configuration import get_config
from src.config.HttpConfiguration import HttpConfiguration
from src.services.user_service import get_user_service, UserService
from src.web.routers.schedule_router import schedule_router
from src.web.routers.service_router import service_router
from src.web.routers.user_router import user_router
from src.web.routers.video_router import video_router
from src.web.exception_handlers import register_exception_handlers


def create_http_app(config: ParseResults) -> FastAPI:
    app = FastAPI()

    user_service: UserService = get_user_service(config)

    app.include_router(user_router(user_service))
    app.include_router(schedule_router())
    app.include_router(video_router())
    app.include_router(service_router())

    register_exception_handlers(app)

    return app


parse_results: ParseResults = get_config()
http_app = create_http_app(parse_results)

if __name__ == "__main__":
    import uvicorn

    http_config: HttpConfiguration = HttpConfiguration.parse(parse_results)
    uvicorn.run(http_app, host=http_config.host, port=http_config.port)
