from abc import ABC, abstractmethod

import boto3
from pydantic import EmailStr
from pyparsing import ParseResults

from src.config.AwsCognitoConfiguration import AwsCognitoConfiguration
from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_validation_service import (
    UserValidationService,
    get_user_validation_service,
)


class UserService(ABC):
    @abstractmethod
    def create_user(self, email: EmailStr, password: str) -> User:
        pass


class CognitoUserService(UserService):
    def __init__(
        self,
        user_validation_service: UserValidationService,
        cognito_idp_client,
        cognito_user_pool_id: str,
        cognito_user_pool_client_id: str,
    ):
        self._user_validation_service = user_validation_service
        self._cognito_idp_client = cognito_idp_client
        self._cognito_user_pool_id = cognito_user_pool_id
        self._cognito_user_pool_client_id = cognito_user_pool_client_id

    def create_user(self, email: EmailStr, password: str) -> User:
        user = self._user_validation_service.get_user(email=email, password=password)

        try:
            self._cognito_idp_client.sign_up(
                ClientId=self._cognito_user_pool_client_id,
                Username=email,
                Password=password,
                UserAttributes=[
                    {"Name": "sub", "Value": user.id},
                    {
                        "Name": "email",
                        "Value": email,
                    },
                    {
                        "Name": "given_name",
                        "Value": user.first_name,
                    },
                    {"Name": "family_name", "Value": user.last_name},
                    {"Name": "email_verified", "Value": "true"},
                ],
            )
        except self._cognito_idp_client.exceptions.UsernameExistsException:
            raise ResourceConflictException(f'User with email "{email}" already exists')

        self._cognito_idp_client.admin_confirm_sign_up(
            UserPoolId=self._cognito_user_pool_id, Username=email
        )

        return user


def get_user_service(parse_results: ParseResults) -> UserService:
    user_validation_service = get_user_validation_service(parse_results)

    aws_cognito_configuration = AwsCognitoConfiguration.parse(parse_results)
    cognito_client = boto3.client(
        "cognito-idp", endpoint_url=aws_cognito_configuration.endpoint_url
    )

    user_service = CognitoUserService(
        user_validation_service,
        cognito_client,
        cognito_user_pool_id=aws_cognito_configuration.user_pool_id,
        cognito_user_pool_client_id=aws_cognito_configuration.client_id,
    )

    return user_service
