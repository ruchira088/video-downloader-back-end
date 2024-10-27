from dataclasses import dataclass
from typing import Optional


@dataclass
class AwsCognitoConfiguration:
    user_pool_id: str
    client_id: str
    url: Optional[str] = None
