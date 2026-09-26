from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.config.configuration import AppConfiguration
from src.services.access_token_verifier import (
    CognitoAccessTokenVerifier,
    jwks_signing_key_resolver,
)
from src.services.authentication_service import CognitoAuthenticationService
from src.services.cognito_helpers import create_cognito_client, get_client_secret
from src.services.scheduling_service import get_scheduling_service
from src.services.system_service import SystemService, SystemServiceImpl
from src.services.user_service import UserService, get_user_service
from src.web.depends.authentication import authenticated_user_dependency
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.authentication_router import authentication_router
from src.web.routers.schedule_router import schedule_router
from src.web.routers.service_router import service_router
from src.web.routers.user_router import user_router
from src.web.routers.video_router import video_router

# The origins the main API allows (its HTTP_ALLOWED_ORIGINS default): the web app on any ruchij.com
# host, and local development on localhost or a private network address. The web app calls
# POST /user after each main-API login, so every user already has a fallback account.
ALLOWED_ORIGIN_REGEX = (
    r"https?://("
    r"([a-z0-9-]+\.)*ruchij\.com"
    r"|([a-z0-9-]+\.)*localhost"
    r"|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.\d+\.\d+\.\d+"
    r")(:\d+)?"
)


def create_http_app(app_configuration: AppConfiguration) -> FastAPI:
    app = FastAPI()
    # Bearer tokens, not cookies, so no credentialed requests are needed
    app.add_middleware(
        CORSMiddleware,
        allow_origin_regex=ALLOWED_ORIGIN_REGEX,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    cognito_configuration = app_configuration.cognito
    cognito_idp_client = create_cognito_client(cognito_configuration)
    client_secret = get_client_secret(
        cognito_idp_client,
        cognito_configuration.user_pool_id,
        cognito_configuration.client_id,
    )

    user_service: UserService = get_user_service(app_configuration, cognito_idp_client)
    access_token_verifier = CognitoAccessTokenVerifier(
        issuer=cognito_configuration.token_issuer(),
        client_id=cognito_configuration.client_id,
        signing_key_resolver=jwks_signing_key_resolver(
            cognito_configuration.jwks_url()
        ),
    )
    authentication_service = CognitoAuthenticationService(
        cognito_idp_client,
        cognito_configuration.client_id,
        client_secret,
        access_token_verifier,
    )
    authenticated_user = authenticated_user_dependency(authentication_service)
    system_service: SystemService = SystemServiceImpl(app_configuration)
    scheduling_service = get_scheduling_service(app_configuration)

    app.include_router(user_router(user_service))
    app.include_router(authentication_router(authentication_service))
    app.include_router(schedule_router(scheduling_service, authenticated_user))
    app.include_router(video_router())
    app.include_router(service_router(system_service))

    register_exception_handlers(app)

    return app
