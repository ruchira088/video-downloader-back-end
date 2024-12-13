import unittest
from typing import Union
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_service import UserService, CognitoUserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_user, sample_password
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)

        self.user_validation_service: Union[UserValidationService, MagicMock] = (
            MagicMock()
        )

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
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

    def test_creating_duplicate_user_throws_resource_conflict_exception(self):
        self.user_validation_service.get_user.return_value = sample_user

        self.user_service.create_user(email=sample_user.email, password=sample_password)

        with self.assertRaises(ResourceConflictException):
            self.user_service.create_user(
                email=sample_user.email, password=sample_password
            )
