import unittest
from unittest.mock import MagicMock, patch

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.models.user import User
from src.services.user_service import CognitoUserService
from src.web.depends.authentication import authenticated_user_dependency
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.authentication_router import authentication_router
from tests.services.test_data_helpers import sample_password, sample_user
from tests.services.test_service_helpers import (
    moto_access_token_verifier,
    setup_cognito,
)


@mock_aws
class TestAuthenticationRouter(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        self.cognito_client = cognito_details.cognito_client
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        ).create_user(email=sample_user.email, password=sample_password)

        authentication_service = CognitoAuthenticationService(
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
            client_secret_key=cognito_details.user_pool_client_secret,
            access_token_verifier=moto_access_token_verifier(cognito_details),
        )
        authenticated_user = authenticated_user_dependency(authentication_service)

        app = FastAPI()
        app.include_router(authentication_router(authentication_service))

        @app.get("/whoami")
        def whoami(user: User = Depends(authenticated_user)):
            return {"id": user.id, "role": user.role}

        register_exception_handlers(app)
        self.client = TestClient(app)

    def _login(self) -> str:
        response = self.client.post(
            "/authentication/login",
            json={"email": sample_user.email, "password": sample_password},
        )
        self.assertEqual(response.status_code, 200)
        return response.json()["access_token"]

    def test_login_and_use_the_access_token(self):
        token = self._login()

        response = self.client.get(
            "/whoami", headers={"Authorization": f"Bearer {token}"}
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"id": sample_user.id, "role": "User"})

    def test_login_with_wrong_password_returns_401(self):
        response = self.client.post(
            "/authentication/login",
            json={"email": sample_user.email, "password": "wrong-password"},
        )

        self.assertEqual(response.status_code, 401)

    def test_missing_authorization_header_returns_401(self):
        self.assertEqual(self.client.get("/whoami").status_code, 401)
        self.assertEqual(self.client.delete("/authentication/logout").status_code, 401)

    def test_login_of_an_unknown_user_returns_401(self):
        response = self.client.post(
            "/authentication/login",
            json={"email": "nobody@ruchij.com", "password": sample_password},
        )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "Incorrect credentials"})

    def _login_failing_with(self, exception_name: str):
        cognito_client = self.cognito_client
        error = getattr(cognito_client.exceptions, exception_name)(
            {"Error": {"Code": exception_name, "Message": "x"}}, "InitiateAuth"
        )

        with patch.object(cognito_client, "initiate_auth", side_effect=error):
            return self.client.post(
                "/authentication/login",
                json={"email": sample_user.email, "password": sample_password},
            )

    def test_login_requiring_a_password_reset_returns_403(self):
        response = self._login_failing_with("PasswordResetRequiredException")

        self.assertEqual(response.status_code, 403)
        self.assertIn("password reset", response.json()["detail"])

    def test_throttled_login_returns_429(self):
        response = self._login_failing_with("TooManyRequestsException")

        self.assertEqual(response.status_code, 429)

    def test_non_bearer_authorization_header_returns_401(self):
        response = self.client.get("/whoami", headers={"Authorization": "Basic abc"})

        self.assertEqual(response.status_code, 401)

    def test_logout_invalidates_the_token(self):
        token = self._login()
        headers = {"Authorization": f"Bearer {token}"}

        logout_response = self.client.delete("/authentication/logout", headers=headers)

        self.assertEqual(logout_response.status_code, 200)
        self.assertEqual(logout_response.json()["id"], sample_user.id)
        self.assertEqual(self.client.get("/whoami", headers=headers).status_code, 401)
