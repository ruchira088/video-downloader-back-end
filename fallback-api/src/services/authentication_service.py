from abc import ABC, abstractmethod


class AuthenticationService(ABC):
    @abstractmethod
    def login(self, email: str, password: str):
        pass

    @abstractmethod
    def authenticate(self, token: str):
        pass

    @abstractmethod
    def logout(self, token: str):
        pass

def get_authentication_service() -> AuthenticationService:
    pass