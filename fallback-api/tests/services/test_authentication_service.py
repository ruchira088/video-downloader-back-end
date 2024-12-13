import unittest
from typing import Union
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.user_service import UserService, CognitoUserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_user, sample_password
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoAuthenticationService(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        user_validation_service: Union[UserValidationService, MagicMock] = (
            MagicMock()
        )
        user_validation_service.get_user.return_value = sample_user

        user_service: UserService = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
        )

        user_service.create_user(
            email=sample_user.email, password=sample_password
        )

        self.cognito_authentication_service: CognitoAuthenticationService = CognitoAuthenticationService(
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
            client_secret_key=cognito_details.user_pool_client_secret
        )

    def test_authenticate_user(self):
        response = self.cognito_authentication_service.login(sample_user.email, sample_password)
