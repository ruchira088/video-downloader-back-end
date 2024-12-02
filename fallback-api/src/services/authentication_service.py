from abc import ABC, abstractmethod

from pydantic import EmailStr


class AuthenticationService(ABC):
    @abstractmethod
    def login(self, email: EmailStr, password: str):
        pass

    @abstractmethod
    def authenticate(self, token: str):
        pass

    @abstractmethod
    def logout(self, token: str):
        pass


class CognitoAuthenticationService(AuthenticationService):
    def __init__(self, cognito_idp_client, cognito_user_pool_client_id: str):
        self.cognito_idp_client = cognito_idp_client
        self.cognito_user_pool_client_id = cognito_user_pool_client_id

    def login(self, email: EmailStr, password: str):
        pass

    def authenticate(self, token: str):
        pass

    def logout(self, token: str):
        pass


def get_authentication_service() -> AuthenticationService:
    pass
