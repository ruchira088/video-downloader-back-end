from flask import request
from werkzeug.exceptions import Unauthorized

from src.services.authentication_service import AuthenticationService, get_authentication_service

authentication_service: AuthenticationService = get_authentication_service()

def authenticated(func):
    def inner(*args, **kwargs):
        authorization_header = request.headers.get('Authorization')

        if not authorization_header:
            raise Unauthorized('Authorization header not found')

        authentication_token = authorization_header.split(' ')[1]
        user = authentication_service.authenticate(authentication_token)

        request.user = user

        return func(*args, **kwargs)

    return inner
