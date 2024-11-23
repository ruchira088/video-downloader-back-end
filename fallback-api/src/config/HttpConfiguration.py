from pydantic import BaseModel


class HttpConfiguration(BaseModel):
    host: str
    port: int