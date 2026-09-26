import unittest
from unittest.mock import MagicMock, patch

from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidPasswordException,
)
from src.services.models.user import Role, User
from src.services.user_service import CognitoUserService, UserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_admin, sample_password, sample_user
from tests.services.test_service_helpers import (
    moto_access_token_verifier,
    setup_cognito,
)

# Meets moto's default password policy, which needs upper and lower case, a digit and a symbol
NEW_PASSWORD = "New-Passw0rd-9!"


def _attributes(cognito_client, user_pool_id: str, username: str) -> dict[str, str]:
    response = cognito_client.admin_get_user(UserPoolId=user_pool_id, Username=username)
    return {a["Name"]: a["Value"] for a in response["UserAttributes"]}


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)
        self.cognito_client = self.cognito_details.cognito_client

        self.user_validation_service = MagicMock(spec=UserValidationService)

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_id=self.cognito_details.user_pool_id,
        )

    def _attributes(self, email: str) -> dict[str, str]:
        return _attributes(
            self.cognito_client, self.cognito_details.user_pool_id, email
        )

    def _login(self, email: str, password: str) -> None:
        CognitoAuthenticationService(
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_client_id=self.cognito_details.user_pool_client_id,
            client_secret_key=self.cognito_details.user_pool_client_secret,
            access_token_verifier=moto_access_token_verifier(self.cognito_details),
        ).login(email, password)

    def _upsert(self, user: User, password: str = sample_password):
        self.user_validation_service.get_user.return_value = user
        return self.user_service.upsert_user(email=user.email, password=password)

    def test_a_new_user_is_created(self):
        upsert = self._upsert(sample_user)

        self.user_validation_service.get_user.assert_called_with(
            email=sample_user.email, password=sample_password
        )
        self.assertIs(upsert.user, sample_user)
        self.assertTrue(upsert.created)

        attributes = self._attributes(sample_user.email)
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["custom:role"], "User")
        self.assertEqual(attributes["given_name"], sample_user.first_name)
        self._login(sample_user.email, sample_password)

    def test_a_new_admin_is_created_with_the_admin_role(self):
        self._upsert(sample_admin)

        self.assertEqual(self._attributes(sample_admin.email)["custom:role"], "Admin")

    def test_an_existing_user_is_refreshed_from_the_main_api(self):
        self._upsert(sample_user)

        # The main API now has a new name, the Admin role and a new password
        promoted = sample_user.model_copy(
            update={"first_name": "Johnny", "role": Role.ADMIN}
        )
        upsert = self._upsert(promoted, password=NEW_PASSWORD)

        self.assertFalse(upsert.created)
        attributes = self._attributes(sample_user.email)
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["given_name"], "Johnny")
        self.assertEqual(attributes["custom:role"], "Admin")
        self._login(sample_user.email, NEW_PASSWORD)
        with self.assertRaises(IncorrectCredentialsException):
            self._login(sample_user.email, sample_password)

    def test_a_user_signed_up_before_roles_existed_gets_their_role(self):
        self.cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username=sample_admin.email,
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": sample_admin.email},
                {"Name": "custom:user_id", "Value": sample_admin.id},
            ],
        )

        upsert = self._upsert(sample_admin)

        self.assertFalse(upsert.created)
        self.assertEqual(self._attributes(sample_admin.email)["custom:role"], "Admin")

    def test_an_email_now_belonging_to_another_main_side_user_gets_a_new_account(self):
        self._upsert(sample_user)

        # The original user was deleted on the main API and the email reused
        successor = sample_user.model_copy(update={"id": "successor-id"})
        upsert = self._upsert(successor, password=NEW_PASSWORD)

        self.assertTrue(upsert.created)
        self.assertEqual(
            self._attributes(sample_user.email)["custom:user_id"], "successor-id"
        )
        self._login(sample_user.email, NEW_PASSWORD)

    def test_a_user_created_concurrently_is_refreshed_instead(self):
        self._upsert(sample_user)
        not_found = self.cognito_client.exceptions.UserNotFoundException(
            {"Error": {"Code": "UserNotFoundException", "Message": "x"}}, "AdminGetUser"
        )

        # Another request creates the user between this one's lookup and its create
        with patch.object(self.cognito_client, "admin_get_user", side_effect=not_found):
            upsert = self._upsert(sample_user, password=NEW_PASSWORD)

        self.assertFalse(upsert.created)
        self._login(sample_user.email, NEW_PASSWORD)

    def test_a_weak_password_rolls_back_a_new_user(self):
        with self.assertRaises(InvalidPasswordException):
            self._upsert(sample_user, password="weak")

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_a_weak_password_keeps_an_existing_user_and_their_old_password(self):
        self._upsert(sample_user)

        with self.assertRaises(InvalidPasswordException):
            self._upsert(sample_user, password="weak")

        self._login(sample_user.email, sample_password)

    def test_credentials_the_main_api_rejects_change_nothing(self):
        self._upsert(sample_user)
        self.user_validation_service.get_user.side_effect = (
            IncorrectCredentialsException()
        )

        with self.assertRaises(IncorrectCredentialsException):
            self.user_service.upsert_user(
                email=sample_user.email, password=NEW_PASSWORD
            )

        self._login(sample_user.email, sample_password)


class TestCognitoUserServicePasswordFailure(unittest.TestCase):
    def _user_service(self, cognito_client: MagicMock) -> CognitoUserService:
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        # A new user: the lookup finds no account
        not_found = type("UserNotFoundException", (Exception,), {})
        cognito_client.exceptions.UserNotFoundException = not_found
        cognito_client.admin_get_user.side_effect = not_found()

        return CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_client,
            cognito_user_pool_id="pool-id",
        )

    def test_a_new_user_is_deleted_when_setting_the_password_fails(self):
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError(
            "weak password"
        )

        with self.assertRaises(RuntimeError):
            self._user_service(cognito_client).upsert_user(
                email=sample_user.email, password="weak"
            )

        cognito_client.admin_delete_user.assert_called_once_with(
            UserPoolId="pool-id", Username=sample_user.email
        )

    def test_the_original_error_is_raised_when_the_rollback_delete_also_fails(self):
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError(
            "weak password"
        )
        cognito_client.admin_delete_user.side_effect = RuntimeError("throttled")

        with self.assertRaisesRegex(RuntimeError, "weak password"):
            self._user_service(cognito_client).upsert_user(
                email=sample_user.email, password="weak"
            )
