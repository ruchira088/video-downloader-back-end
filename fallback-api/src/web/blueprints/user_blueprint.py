import json
from dataclasses import dataclass

from flask import Blueprint, request, Response
from marshmallow import Schema, fields, EXCLUDE

from src.services.user.user_service import UserService, CognitoUserService, User

user_blueprint = Blueprint('user', __name__, url_prefix='/user')

user_service: UserService = None


class UserSignupRequestSchema(Schema):
    email = fields.Email(required=True)
    password = fields.Str(required=True)


@dataclass
class UserResponse:
    id: str
    email: str
    firstName: str
    lastName: str


@user_blueprint.post('/')
def sign_up():
    user_signup_request = UserSignupRequestSchema().load(request.json, unknown=EXCLUDE)
    user: User = user_service.create_user(user_signup_request['email'], user_signup_request['password'])

    return Response(
        status=201,
        response=json.dumps(user),
        headers={'Content-Type': 'application/json'}
    )
