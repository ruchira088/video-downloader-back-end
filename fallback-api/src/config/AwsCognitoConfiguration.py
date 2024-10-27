from dataclasses import dataclass
from typing import Optional


@dataclass
class AwsCognitoConfiguration:
    client_id: str
    url: Optional[str] = None
