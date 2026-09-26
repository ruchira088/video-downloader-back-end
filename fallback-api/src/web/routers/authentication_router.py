from fastapi import APIRouter, Depends
from pydantic import BaseModel, EmailStr

from src.services.authentication_service import (
    AuthenticationService,
    AuthenticationToken,
)
from src.web.depends.authentication import bearer_token
from src.web.routers.user_router import UserResponse


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


def authentication_router(authentication_service: AuthenticationService) -> APIRouter:
    router = APIRouter(prefix="/authentication")

    @router.post("/login", response_model=AuthenticationToken)
    def login(login_request: LoginRequest):
        return authentication_service.login(login_request.email, login_request.password)

    @router.delete("/logout", response_model=UserResponse)
    def logout(token: str = Depends(bearer_token)):
        return UserResponse.from_user(authentication_service.logout(token))

    return router
