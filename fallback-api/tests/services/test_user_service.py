import unittest
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_service import CognitoUserService, UserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_admin, sample_password, sample_user
from tests.services.test_service_helpers import setup_cognito


def _attributes(cognito_client, user_pool_id: str, username: str) -> dict[str, str]:
    response = cognito_client.admin_get_user(UserPoolId=user_pool_id, Username=username)
    return {a["Name"]: a["Value"] for a in response["UserAttributes"]}


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)

        self.user_validation_service: UserValidationService | MagicMock = MagicMock()

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=self.cognito_details.cognito_client,
            cognito_user_pool_id=self.cognito_details.user_pool_id,
        )

    def test_create_user(self):
        self.user_validation_service.get_user.return_value = sample_user

        created_user: User = self.user_service.create_user(
            email=sample_user.email, password=sample_password
        )

        self.user_validation_service.get_user.assert_called_with(
            email=sample_user.email, password=sample_password
        )
        self.assertIs(sample_user, created_user)

        attributes = _attributes(
            self.cognito_details.cognito_client,
            self.cognito_details.user_pool_id,
            sample_user.email,
        )
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["custom:role"], "User")
        self.assertEqual(attributes["given_name"], sample_user.first_name)

    def test_create_admin_user_stores_admin_role(self):
        self.user_validation_service.get_user.return_value = sample_admin

        self.user_service.create_user(
            email=sample_admin.email, password=sample_password
        )

        attributes = _attributes(
            self.cognito_details.cognito_client,
            self.cognito_details.user_pool_id,
            sample_admin.email,
        )
        self.assertEqual(attributes["custom:role"], "Admin")

    def test_creating_duplicate_user_throws_resource_conflict_exception(self):
        self.user_validation_service.get_user.return_value = sample_user

        self.user_service.create_user(email=sample_user.email, password=sample_password)

        with self.assertRaises(ResourceConflictException):
            self.user_service.create_user(
                email=sample_user.email, password=sample_password
            )


class TestCognitoUserServicePasswordFailure(unittest.TestCase):
    def test_user_is_deleted_when_setting_the_password_fails(self):
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError(
            "weak password"
        )

        user_service = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_client,
            cognito_user_pool_id="pool-id",
        )

        with self.assertRaises(RuntimeError):
            user_service.create_user(email=sample_user.email, password="weak")

        cognito_client.admin_delete_user.assert_called_once_with(
            UserPoolId="pool-id", Username=sample_user.email
        )
