from datetime import datetime

from pydantic import EmailStr, BaseModel


class User(BaseModel):
    id: str
    created_at: datetime
    email: EmailStr
    first_name: str
    last_name: str
