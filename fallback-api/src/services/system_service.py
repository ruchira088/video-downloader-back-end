from abc import ABC
from datetime import datetime

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from src.config.configuration import AppConfiguration


class SystemInfo(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    service_name: str
    timestamp: datetime


class SystemService(ABC):
    def perform_health_check(self):
        pass

    def get_system_info(self) -> SystemInfo:
        pass


class SystemServiceImpl(SystemService):
    def __init__(self, app_configuration: AppConfiguration):
        self._app_configuration = app_configuration

    def perform_health_check(self):
        return True

    def get_system_info(self) -> SystemInfo:
        return SystemInfo(
            service_name="video-downloader-fallback-api",
            timestamp=datetime.now().astimezone(),
        )
