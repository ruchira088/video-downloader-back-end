from collections.abc import Callable

from fastapi import Depends, Header, HTTPException

from src.services.authentication_service import AuthenticationService
from src.services.models.user import User


def bearer_token(authorization: str | None = Header(None)) -> str:
    if authorization is None:
        raise HTTPException(status_code=401, detail="Missing Authorization header")

    scheme, _, token = authorization.partition(" ")

    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=401, detail="Invalid Authorization header format"
        )

    return token


def authenticated_user_dependency(
    authentication_service: AuthenticationService,
) -> Callable[..., User]:
    def get_authenticated_user(token: str = Depends(bearer_token)) -> User:
        return authentication_service.authenticate(token)

    return get_authenticated_user
