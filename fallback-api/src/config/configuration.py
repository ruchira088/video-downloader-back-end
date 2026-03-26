import os
from pathlib import Path
from typing import Optional

from pydantic import BaseModel
from pyhocon import ConfigFactory
from pyparsing import ParseResults

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.config.http_configuration import HttpConfiguration
from src.config.video_downloader_configuration import VideoDownloaderConfiguration


class AppConfiguration(BaseModel):
    cognito: AwsCognitoConfiguration
    http: HttpConfiguration
    video_downloader: VideoDownloaderConfiguration

    @classmethod
    def parse(cls, parse_results: ParseResults) -> "AppConfiguration":
        cognito_configuration = AwsCognitoConfiguration.parse(parse_results)
        http_configuration = HttpConfiguration.parse(parse_results)
        video_downloader_configuration = VideoDownloaderConfiguration.parse(parse_results)

        app_configuration = AppConfiguration(
            cognito=cognito_configuration,
            http=http_configuration,
            video_downloader=video_downloader_configuration,
        )

        return app_configuration


def get_config() -> ParseResults:
    config_file_path_str: Optional[str] = os.environ.get("CONFIG_FILE_PATH")

    if config_file_path_str is None:
        config_file_path = _default_config_file_path()
    else:
        config_file_path = Path(config_file_path_str)

    if config_file_path.is_file():
        parse_results = ConfigFactory.parse_file(config_file_path)
        return parse_results
    else:
        raise FileNotFoundError(config_file_path)


def _default_config_file_path() -> Path:
    return Path(__file__).parent.parent.parent.joinpath("application.conf")
