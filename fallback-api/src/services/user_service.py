import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

from pydantic import EmailStr

from src.config.configuration import AppConfiguration
from src.services.exceptions import InvalidPasswordException
from src.services.models.user import User
from src.services.user_validation_service import (
    UserValidationService,
    VideoDownloaderUserValidationService,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class UserUpsert:
    user: User
    # False when the user already existed and was refreshed
    created: bool


class UserService(ABC):
    @abstractmethod
    def upsert_user(self, email: EmailStr, password: str) -> UserUpsert:
        """Create the user from the main API's copy, or refresh an existing one's name, role and
        password from it, once the main API accepts the email and password."""


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

    def upsert_user(self, email: EmailStr, password: str) -> UserUpsert:
        # Every call re-checks the password with the main API, so only the account's owner can
        # create or refresh it, and a password changed on the main API reaches the fallback.
        user = self._user_validation_service.get_user(email=email, password=password)

        existing_user_id = self._existing_user_id(email)
        if existing_user_id is not None and existing_user_id != user.id:
            # The email now belongs to another main-side user (the old one was deleted), and
            # custom:user_id can't be changed, so replace the account rather than let the new
            # user sign in as the old one.
            logger.warning(
                "Replacing the fallback account of user %s, whose email now belongs to user %s",
                existing_user_id,
                user.id,
            )
            self._cognito_idp_client.admin_delete_user(
                UserPoolId=self._cognito_user_pool_id, Username=email
            )
            existing_user_id = None

        if existing_user_id is None and self._create(email, user):
            self._set_password(email, password, user, roll_back=True)
            return UserUpsert(user, created=True)

        self._refresh(email, user)
        self._set_password(email, password, user, roll_back=False)
        return UserUpsert(user, created=False)

    def _existing_user_id(self, email: str) -> str | None:
        try:
            response = self._cognito_idp_client.admin_get_user(
                UserPoolId=self._cognito_user_pool_id, Username=email
            )
        except self._cognito_idp_client.exceptions.UserNotFoundException:
            return None

        attributes = {a["Name"]: a["Value"] for a in response["UserAttributes"]}
        return attributes.get("custom:user_id")

    def _create(self, email: str, user: User) -> bool:
        """False when a concurrent request created the user first."""
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
            return True
        except self._cognito_idp_client.exceptions.UsernameExistsException:
            return False

    def _refresh(self, email: str, user: User) -> None:
        # custom:user_id is immutable, and was checked to match above
        self._cognito_idp_client.admin_update_user_attributes(
            UserPoolId=self._cognito_user_pool_id,
            Username=email,
            UserAttributes=[
                {"Name": "given_name", "Value": user.first_name},
                {"Name": "family_name", "Value": user.last_name},
                {"Name": "custom:role", "Value": user.role.value},
            ],
        )

    def _set_password(
        self, email: str, password: str, user: User, roll_back: bool
    ) -> None:
        try:
            self._cognito_idp_client.admin_set_user_password(
                UserPoolId=self._cognito_user_pool_id,
                Username=email,
                Password=password,
                Permanent=True,
            )
        except Exception as error:
            if roll_back:
                # A user just created without a password could never log in, so undo the
                # creation. A failed undo is only logged, so the caller still sees why sign-up
                # failed rather than the rollback's error. An existing user keeps their old
                # password instead.
                try:
                    self._cognito_idp_client.admin_delete_user(
                        UserPoolId=self._cognito_user_pool_id, Username=email
                    )
                except Exception:
                    logger.exception(
                        "Unable to delete user %s after setting their password failed",
                        user.id,
                    )

            # In tests, cognito_idp_client can be a MagicMock, whose .exceptions.* attributes
            # are themselves MagicMocks rather than exception classes; isinstance() would raise
            # TypeError against those, so only compare once we know it's a real exception type.
            invalid_password_exception_type = (
                self._cognito_idp_client.exceptions.InvalidPasswordException
            )
            if isinstance(invalid_password_exception_type, type) and isinstance(
                error, invalid_password_exception_type
            ):
                raise InvalidPasswordException(
                    f"Password does not meet the fallback password policy: {error}"
                ) from error

            raise


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
