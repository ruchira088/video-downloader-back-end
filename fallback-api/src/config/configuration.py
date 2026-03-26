import os
from pathlib import Path
from typing import Optional

from pydantic import BaseModel
from pyhocon import ConfigFactory, ConfigTree

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.config.http_configuration import HttpConfiguration
from src.config.video_downloader_configuration import VideoDownloaderConfiguration


class AppConfiguration(BaseModel):
    cognito: AwsCognitoConfiguration
    http: HttpConfiguration
    video_downloader: VideoDownloaderConfiguration

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "AppConfiguration":
        cognito_configuration = AwsCognitoConfiguration.parse(config_tree)
        http_configuration = HttpConfiguration.parse(config_tree)
        video_downloader_configuration = VideoDownloaderConfiguration.parse(config_tree)

        app_configuration = AppConfiguration(
            cognito=cognito_configuration,
            http=http_configuration,
            video_downloader=video_downloader_configuration,
        )

        return app_configuration


def get_config_tree() -> ConfigTree:
    config_file_path_str: Optional[str] = os.environ.get("CONFIG_FILE_PATH")

    if config_file_path_str is None:
        config_file_path = _default_config_file_path()
    else:
        config_file_path = Path(config_file_path_str)

    if config_file_path.is_file():
        config_tree = ConfigFactory.parse_file(config_file_path)
        return config_tree
    else:
        raise FileNotFoundError(config_file_path)


def _default_config_file_path() -> Path:
    return Path(__file__).parent.parent.parent.joinpath("application.conf")
