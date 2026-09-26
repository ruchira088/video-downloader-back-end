from fastapi import APIRouter, Response
from pydantic import BaseModel, EmailStr

from src.services.user_service import User, UserService


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

    # Signs a user up, or refreshes an existing user's name, role and password from the main
    # API: 201 when the user was created, 200 when refreshed. Safe to call on every login.
    @router.post("", status_code=201, response_model=UserResponse)
    def sign_up(user_signup_request: UserSignupRequest, response: Response):
        upsert = user_service.upsert_user(
            user_signup_request.email, user_signup_request.password
        )
        if not upsert.created:
            response.status_code = 200
        return UserResponse.from_user(upsert.user)

    return router
