import hmac
from base64 import b64encode
from hashlib import sha256
from abc import ABC, abstractmethod

from pydantic import EmailStr, BaseModel

from src.services.exceptions import IncorrectCredentialsException
from src.services.models.user import User


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
    def logout(self, token: str):
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
                    "SECRET_HASH": self._secret_hash(email),
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

    def _secret_hash(self, email: EmailStr) -> str:
        message = bytes(email + self._cognito_user_pool_client_id, encoding="utf-8")
        key = bytes(self._client_secret_key, encoding="utf-8")

        secret_hash = b64encode(
            hmac.new(key, message, digestmod=sha256).digest()
        ).decode()

        return secret_hash

    def authenticate(self, token: str) -> User:
        response = self._cognito_idp_client.get_user(AccessToken=token)
        user_attributes = response["UserAttributes"]

        def _get_attribute(name: str) -> str:
            for attribute in user_attributes:
                if attribute["Name"] == name:
                    return attribute["Value"]

            raise ValueError(f'Attribute "{name}" not found')

        user_id: str = _get_attribute("custom:user_id")
        email: EmailStr = _get_attribute("email")
        first_name: str = _get_attribute("given_name")
        last_name: str = _get_attribute("family_name")

        user = User(
            id=user_id,
            email=email,
            first_name=first_name,
            last_name=last_name,
        )

        return user

    def logout(self, token: str):
        pass


def get_authentication_service() -> AuthenticationService:
    pass
