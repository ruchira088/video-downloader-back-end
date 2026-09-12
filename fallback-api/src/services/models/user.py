from pydantic import BaseModel, EmailStr


class User(BaseModel):
    id: str
    email: EmailStr
    first_name: str
    last_name: str
