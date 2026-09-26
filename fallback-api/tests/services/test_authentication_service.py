import unittest
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.authentication_service import (
    AuthenticationToken,
    CognitoAuthenticationService,
)
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidAuthenticationTokenException,
)
from src.services.models.user import Role, User
from src.services.user_service import CognitoUserService, UserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_password, sample_user
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoAuthenticationService(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)
        cognito_details = self.cognito_details
        user_validation_service: UserValidationService | MagicMock = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        user_service: UserService = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
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

    def test_authenticate_user_with_invalid_access_token(self):
        with self.assertRaises(InvalidAuthenticationTokenException):
            self.cognito_authentication_service.authenticate("invalid-token")

    def test_logout_user_with_valid_access_token(self):
        auth_token: AuthenticationToken = self.cognito_authentication_service.login(
            sample_user.email, sample_password
        )

        user: User = self.cognito_authentication_service.logout(auth_token.access_token)

        assert user.id == sample_user.id
        assert user.email == sample_user.email
        assert user.first_name == sample_user.first_name
        assert user.last_name == sample_user.last_name

        with self.assertRaises(InvalidAuthenticationTokenException):
            self.cognito_authentication_service.logout(auth_token.access_token)

    def test_logout_user_with_invalid_access_token(self):
        with self.assertRaises(InvalidAuthenticationTokenException):
            self.cognito_authentication_service.logout("invalid-token")

    def test_authenticate_returns_the_user_role(self):
        auth_token: AuthenticationToken = self.cognito_authentication_service.login(
            sample_user.email, sample_password
        )

        user: User = self.cognito_authentication_service.authenticate(
            auth_token.access_token
        )

        assert user.role == Role.USER

    def _login_as_user_with_role(self, email: str, role: str) -> User:
        cognito_client = self.cognito_details.cognito_client
        cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username=email,
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": email},
                {"Name": "given_name", "Value": "Role"},
                {"Name": "family_name", "Value": "Holder"},
                {"Name": "custom:user_id", "Value": f"{role}-id"},
                {"Name": "custom:role", "Value": role},
            ],
        )
        cognito_client.admin_set_user_password(
            UserPoolId=self.cognito_details.user_pool_id,
            Username=email,
            Password=sample_password,
            Permanent=True,
        )

        auth_token = self.cognito_authentication_service.login(email, sample_password)
        return self.cognito_authentication_service.authenticate(auth_token.access_token)

    def test_authenticate_returns_the_admin_role_for_an_admin(self):
        user = self._login_as_user_with_role("admin@ruchij.com", "Admin")

        assert user.role == Role.ADMIN

    def test_authenticate_treats_a_role_that_is_not_exactly_admin_as_user(self):
        user = self._login_as_user_with_role("almost-admin@ruchij.com", "admin")

        assert user.role == Role.USER

    def test_authenticate_defaults_to_user_role_when_the_attribute_is_missing(self):
        cognito_client = self.cognito_details.cognito_client
        cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username="legacy@ruchij.com",
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": "legacy@ruchij.com"},
                {"Name": "given_name", "Value": "Legacy"},
                {"Name": "family_name", "Value": "User"},
                {"Name": "custom:user_id", "Value": "legacy-id"},
            ],
        )
        cognito_client.admin_set_user_password(
            UserPoolId=self.cognito_details.user_pool_id,
            Username="legacy@ruchij.com",
            Password=sample_password,
            Permanent=True,
        )

        auth_token = self.cognito_authentication_service.login(
            "legacy@ruchij.com", sample_password
        )
        user = self.cognito_authentication_service.authenticate(auth_token.access_token)

        assert user.id == "legacy-id"
        assert user.role == Role.USER
