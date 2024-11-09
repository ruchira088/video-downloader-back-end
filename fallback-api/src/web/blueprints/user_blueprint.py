import json
from dataclasses import dataclass

from flask import Blueprint, request, Response
from pydantic import BaseModel, EmailStr

from src.services.user.user_service import UserService, CognitoUserService, User
from src.services.user.user_validation_service import UserValidationService, get_user_validation_service

user_blueprint = Blueprint('user', __name__, url_prefix='/user')

user_validation_service: UserValidationService = get_user_validation_service()
user_service: UserService = CognitoUserService(user_validation_service)

class UserSignupRequest(BaseModel):
    email: EmailStr
    password: str


@dataclass
class UserResponse:
    id: str
    email: str
    firstName: str
    lastName: str


@user_blueprint.post('/')
def sign_up():
    user_signup_request = UserSignupRequest.model_validate_json(request.json)
    user: User = user_service.create_user(user_signup_request['email'], user_signup_request['password'])

    return Response(
        status=201,
        response=json.dumps(user),
        headers={'Content-Type': 'application/json'}
    )
