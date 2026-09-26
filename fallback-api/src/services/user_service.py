from abc import ABC, abstractmethod

from pydantic import EmailStr

from src.config.configuration import AppConfiguration
from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_validation_service import (
    UserValidationService,
    VideoDownloaderUserValidationService,
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
    ):
        self._user_validation_service = user_validation_service
        self._cognito_idp_client = cognito_idp_client
        self._cognito_user_pool_id = cognito_user_pool_id

    def create_user(self, email: EmailStr, password: str) -> User:
        user = self._user_validation_service.get_user(email=email, password=password)

        # Admin APIs are not limited by the app client's WriteAttributes, so the client can
        # deny users write access to custom:user_id and custom:role.
        try:
            self._cognito_idp_client.admin_create_user(
                UserPoolId=self._cognito_user_pool_id,
                Username=email,
                MessageAction="SUPPRESS",
                UserAttributes=[
                    {"Name": "email", "Value": email},
                    {"Name": "email_verified", "Value": "true"},
                    {"Name": "given_name", "Value": user.first_name},
                    {"Name": "family_name", "Value": user.last_name},
                    {"Name": "custom:user_id", "Value": user.id},
                    {"Name": "custom:role", "Value": user.role.value},
                ],
            )
        except self._cognito_idp_client.exceptions.UsernameExistsException:
            raise ResourceConflictException(f'User with email "{email}" already exists')

        try:
            self._cognito_idp_client.admin_set_user_password(
                UserPoolId=self._cognito_user_pool_id,
                Username=email,
                Password=password,
                Permanent=True,
            )
        except Exception:
            # Without a password the user could never log in, and a retry would hit
            # UsernameExistsException, so undo the creation.
            self._cognito_idp_client.admin_delete_user(
                UserPoolId=self._cognito_user_pool_id, Username=email
            )
            raise

        return user


def get_user_service(
    app_configuration: AppConfiguration, cognito_idp_client
) -> UserService:
    user_validation_service = VideoDownloaderUserValidationService(
        app_configuration.video_downloader.url
    )

    return CognitoUserService(
        user_validation_service,
        cognito_idp_client,
        cognito_user_pool_id=app_configuration.cognito.user_pool_id,
    )
