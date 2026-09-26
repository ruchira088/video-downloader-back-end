from enum import StrEnum

from pydantic import BaseModel, EmailStr


class Role(StrEnum):
    USER = "User"
    ADMIN = "Admin"


class User(BaseModel):
    id: str
    email: EmailStr
    first_name: str
    last_name: str
    role: Role = Role.USER


def parse_role(value: object) -> Role:
    """Anything other than an exact "Admin" is treated as a regular user."""
    return Role.ADMIN if value == Role.ADMIN.value else Role.USER
