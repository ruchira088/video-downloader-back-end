from pydantic import BaseModel
from pyhocon import ConfigTree


class HttpConfiguration(BaseModel):
    host: str
    port: int
    debug: bool

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "HttpConfiguration":
        http_config: ConfigTree = config_tree["http"]

        host: str = http_config["host"]
        port: int = http_config.get_int("port")
        debug: bool = http_config.get_bool("debug", False)

        return HttpConfiguration(host=host, port=port, debug=debug)
