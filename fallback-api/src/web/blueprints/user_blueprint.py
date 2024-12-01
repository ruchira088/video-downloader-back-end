from flask import Blueprint, request, Response
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


def user_blueprint(user_service: UserService) -> Blueprint:
    blueprint = Blueprint("user", __name__, url_prefix="/user")

    @blueprint.post("")
    def sign_up():
        user_signup_request: UserSignupRequest = UserSignupRequest.model_validate_json(
            request.data
        )
        user: User = user_service.create_user(
            user_signup_request.email, user_signup_request.password
        )

        return Response(
            status=201,
            response=UserResponse.from_user(user).model_dump_json(),
            headers={"Content-Type": "application/json"},
        )

    return blueprint
