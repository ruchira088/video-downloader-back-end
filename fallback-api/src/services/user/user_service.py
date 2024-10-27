from abc import ABC, abstractmethod

from src.config.AwsCognitoConfiguration import AwsCognitoConfiguration
from src.services.user.models.user import User
from src.services.user.user_validation_service import UserValidationService


class UserService(ABC):
    @abstractmethod
    def create_user(self, email: str, password: str) -> User:
        pass


class CognitoUserService(UserService):
    def __init__(
            self,
            user_validation_service: UserValidationService,
            aws_cognito_configuration: AwsCognitoConfiguration,
            cognito_idp_client
    ):
        self._cognito_idp_client = cognito_idp_client
        self._user_validation_service = user_validation_service
        self._aws_cognito_configuration = aws_cognito_configuration

    def create_user(self, email: str, password: str) -> User:
        user = self._user_validation_service.get_user(email, password)

        response = self._cognito_idp_client.sign_up(
            ClientId=self._aws_cognito_configuration.client_id,
            Username=user.id,
            Password=password,
            UserAttributes=[
                {
                    'Name': 'email',
                    'Value': email,
                },
                {
                    'Name': 'given_name',
                    'Value': user.first_name,
                },
                {
                    'Name': 'family_name',
                    'Value': user.last_name
                }
            ]
        )

        return user
