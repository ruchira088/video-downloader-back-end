import json

from flask import Blueprint, request, Response
from pydantic import BaseModel, EmailStr
from pyparsing import ParseResults

from src.config.Configuration import get_config
from src.services.user_service import UserService, User, get_user_service
from src.web.json.encoders import composite_json_encoder

user_blueprint = Blueprint('user', __name__, url_prefix='/user')

config: ParseResults = get_config()
user_service: UserService = get_user_service(config)

class UserSignupRequest(BaseModel):
    email: EmailStr
    password: str


class UserResponse(BaseModel):
    id: str
    email: EmailStr
    firstName: str
    lastName: str


@user_blueprint.post('')
def sign_up():
    user_signup_request: UserSignupRequest = UserSignupRequest.model_validate_json(request.data)
    user: User = user_service.create_user(user_signup_request.email, user_signup_request.password)

    return Response(
        status=201,
        response=json.dumps(user, cls=composite_json_encoder),
        headers={'Content-Type': 'application/json'}
    )
