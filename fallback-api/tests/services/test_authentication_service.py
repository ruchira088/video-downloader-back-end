import unittest
from typing import Union
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.authentication_service import (
    CognitoAuthenticationService,
    AuthenticationToken,
)
from src.services.exceptions import IncorrectCredentialsException
from src.services.models.user import User
from src.services.user_service import UserService, CognitoUserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_user, sample_password
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoAuthenticationService(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        user_validation_service: Union[UserValidationService, MagicMock] = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        user_service: UserService = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
        )

        user_service.create_user(email=sample_user.email, password=sample_password)

        self.cognito_authentication_service: CognitoAuthenticationService = (
            CognitoAuthenticationService(
                cognito_idp_client=cognito_details.cognito_client,
                cognito_user_pool_client_id=cognito_details.user_pool_client_id,
                client_secret_key=cognito_details.user_pool_client_secret,
            )
        )

    def test_login_user_correct_credentials(self):
        auth_token: AuthenticationToken = self.cognito_authentication_service.login(
            sample_user.email, sample_password
        )

        assert auth_token.access_token is not None
        assert auth_token.expires_in == 3600
        assert auth_token.token_type == "Bearer"
        assert auth_token.id_token is not None
        assert auth_token.refresh_token is not None

    def test_login_user_incorrect_credentials(self):
        with self.assertRaises(IncorrectCredentialsException):
            self.cognito_authentication_service.login(
                sample_user.email, "invalid-password"
            )

    def test_authenticate_user_with_valid_access_token(self):
        auth_token: AuthenticationToken = self.cognito_authentication_service.login(
            sample_user.email, sample_password
        )

        user: User = self.cognito_authentication_service.authenticate(
            auth_token.access_token
        )

        assert user.id == sample_user.id
        assert user.email == sample_user.email
        assert user.first_name == sample_user.first_name
        assert user.last_name == sample_user.last_name
