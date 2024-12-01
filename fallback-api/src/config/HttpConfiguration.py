from pydantic import BaseModel
from pyparsing import ParseResults


class HttpConfiguration(BaseModel):
    host: str
    port: int
    debug: bool

    @classmethod
    def parse(cls, parse_results: ParseResults) -> "HttpConfiguration":
        config = parse_results.get("http")

        host: str = config["host"]
        port: int = config.get_int("port")
        debug: bool = config.get_bool("debug", False)

        return HttpConfiguration(host=host, port=port, debug=debug)
