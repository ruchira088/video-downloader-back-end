from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from requests import HTTPError

from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidAuthenticationTokenException,
    InvalidPageTokenException,
    InvalidPasswordException,
    InvalidUrlException,
    ResourceConflictException,
    ServiceUnavailableException,
)


def register_exception_handlers(app: FastAPI):
    @app.exception_handler(ResourceConflictException)
    async def handle_resource_conflict(
        request: Request, exc: ResourceConflictException
    ):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.exception_handler(IncorrectCredentialsException)
    async def handle_incorrect_credentials(
        request: Request, exc: IncorrectCredentialsException
    ):
        return JSONResponse(
            status_code=401, content={"detail": "Incorrect credentials"}
        )

    @app.exception_handler(InvalidAuthenticationTokenException)
    async def handle_invalid_token(
        request: Request, exc: InvalidAuthenticationTokenException
    ):
        return JSONResponse(
            status_code=401, content={"detail": "Invalid authentication token"}
        )

    @app.exception_handler(InvalidUrlException)
    async def handle_invalid_url(request: Request, exc: InvalidUrlException):
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(InvalidPageTokenException)
    async def handle_invalid_page_token(
        request: Request, exc: InvalidPageTokenException
    ):
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(InvalidPasswordException)
    async def handle_invalid_password(request: Request, exc: InvalidPasswordException):
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(ServiceUnavailableException)
    async def handle_service_unavailable(
        request: Request, exc: ServiceUnavailableException
    ):
        return JSONResponse(status_code=503, content={"detail": str(exc)})

    @app.exception_handler(HTTPError)
    async def handle_http_error(request: Request, exc: HTTPError):
        if exc.response is None:
            return JSONResponse(
                status_code=502, content={"detail": "Upstream request failed"}
            )

        return JSONResponse(
            status_code=exc.response.status_code, content=exc.response.json()
        )
