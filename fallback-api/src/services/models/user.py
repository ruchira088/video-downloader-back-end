from pydantic import EmailStr, BaseModel


class User(BaseModel):
    id: str
    email: EmailStr
    first_name: str
    last_name: str
