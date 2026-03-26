from fastapi import Header, HTTPException

from src.services.authentication_service import (
    AuthenticationService,
    get_authentication_service,
)
from src.services.models.user import User

authentication_service: AuthenticationService = get_authentication_service()


def get_authenticated_user(authorization: str = Header(...)) -> User:
    if not authorization:
        raise HTTPException(status_code=401, detail="Authorization header not found")

    try:
        token = authorization.split(" ")[1]
    except IndexError:
        raise HTTPException(
            status_code=401, detail="Invalid Authorization header format"
        )

    return authentication_service.authenticate(token)
