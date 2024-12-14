import hmac
from base64 import b64encode
from hashlib import sha256
from abc import ABC, abstractmethod

from pydantic import EmailStr, BaseModel


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
    def authenticate(self, token: str):
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

    def _secret_hash(self, email: EmailStr) -> str:
        message = bytes(email + self._cognito_user_pool_client_id, encoding="utf-8")
        key = bytes(self._client_secret_key, encoding="utf-8")

        secret_hash = b64encode(
            hmac.new(key, message, digestmod=sha256).digest()
        ).decode()

        return secret_hash

    def authenticate(self, token: str):
        pass

    def logout(self, token: str):
        pass


def get_authentication_service() -> AuthenticationService:
    pass
