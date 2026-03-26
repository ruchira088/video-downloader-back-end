from fastapi import APIRouter
from pydantic import BaseModel, EmailStr

from src.services.user_service import UserService, User


class UserSignupRequest(BaseModel):
    email: EmailStr
    password: str


class UserResponse(BaseModel):
    id: str
    email: EmailStr
    firstName: str
    lastName: str

    @classmethod
    def from_user(cls, user: User) -> "UserResponse":
        return cls(
            id=user.id,
            email=user.email,
            firstName=user.first_name,
            lastName=user.last_name,
        )


def user_router(user_service: UserService) -> APIRouter:
    router = APIRouter(prefix="/user")

    @router.post("", status_code=201, response_model=UserResponse)
    def sign_up(user_signup_request: UserSignupRequest):
        user: User = user_service.create_user(
            user_signup_request.email, user_signup_request.password
        )
        return UserResponse.from_user(user)

    return router
