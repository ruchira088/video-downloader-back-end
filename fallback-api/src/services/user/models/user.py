from dataclasses import dataclass
from datetime import datetime


@dataclass
class User:
    id: str
    created_at: datetime
    email: str
    first_name: str
    last_name: str
