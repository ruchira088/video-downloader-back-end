import os
from pathlib import Path
from typing import Optional

from pyhocon import ConfigFactory
from pyparsing import ParseResults


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
