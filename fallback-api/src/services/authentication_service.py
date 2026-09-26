from abc import ABC, abstractmethod

from pydantic import BaseModel, EmailStr

from src.services.cognito_helpers import secret_hash
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidAuthenticationTokenException,
)
from src.services.models.user import User, parse_role


class AuthenticationToken(BaseModel):
    access_token: str
    token_type: str
    expires_in: int
    refresh_token: str
    id_token: str


class AuthenticationService(ABC):
    @abstractmethod
    def login(self, email: EmailStr, password: str) -> AuthenticationToken:
        pass

    @abstractmethod
    def authenticate(self, token: str) -> User:
        pass

    @abstractmethod
    def logout(self, token: str) -> User:
        pass


class CognitoAuthenticationService(AuthenticationService):
    def __init__(
        self,
        cognito_idp_client,
        cognito_user_pool_client_id: str,
        client_secret_key: str,
    ):
        self._cognito_idp_client = cognito_idp_client
        self._cognito_user_pool_client_id = cognito_user_pool_client_id
        self._client_secret_key = client_secret_key

    def login(self, email: EmailStr, password: str) -> AuthenticationToken:
        try:
            response = self._cognito_idp_client.initiate_auth(
                ClientId=self._cognito_user_pool_client_id,
                AuthFlow="USER_PASSWORD_AUTH",
                AuthParameters={
                    "USERNAME": email,
                    "PASSWORD": password,
                    "SECRET_HASH": secret_hash(
                        email,
                        self._cognito_user_pool_client_id,
                        self._client_secret_key,
                    ),
                },
            )

            authentication_result = response["AuthenticationResult"]

            access_token = authentication_result["AccessToken"]
            expires_in = int(authentication_result["ExpiresIn"])
            token_type = authentication_result["TokenType"]
            refresh_token = authentication_result["RefreshToken"]
            id_token = authentication_result["IdToken"]

            authentication_token = AuthenticationToken(
                access_token=access_token,
                token_type=token_type,
                expires_in=expires_in,
                refresh_token=refresh_token,
                id_token=id_token,
            )

            return authentication_token
        except self._cognito_idp_client.exceptions.NotAuthorizedException:
            raise IncorrectCredentialsException()

    def authenticate(self, token: str) -> User:
        try:
            response = self._cognito_idp_client.get_user(AccessToken=token)
        except self._cognito_idp_client.exceptions.NotAuthorizedException:
            raise InvalidAuthenticationTokenException()

        attributes = {a["Name"]: a["Value"] for a in response["UserAttributes"]}

        return User(
            id=attributes["custom:user_id"],
            email=attributes["email"],
            first_name=attributes["given_name"],
            last_name=attributes["family_name"],
            role=parse_role(attributes.get("custom:role")),
        )

    def logout(self, token: str) -> User:
        user = self.authenticate(token)

        try:
            self._cognito_idp_client.global_sign_out(AccessToken=token)
        except self._cognito_idp_client.exceptions.NotAuthorizedException:
            raise InvalidAuthenticationTokenException()

        return user


def get_authentication_service() -> AuthenticationService:
    pass
