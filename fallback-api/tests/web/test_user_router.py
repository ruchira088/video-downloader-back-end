import unittest
from unittest.mock import MagicMock

import requests
from fastapi import FastAPI
from fastapi.testclient import TestClient
from moto import mock_aws

from src.services.user_service import CognitoUserService
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.user_router import user_router
from tests.services.test_data_helpers import sample_user
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestUserRouter(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        self.user_validation_service = MagicMock()
        self.user_validation_service.get_user.return_value = sample_user

        user_service = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        )

        app = FastAPI()
        app.include_router(user_router(user_service))
        register_exception_handlers(app)
        self.client = TestClient(app, raise_server_exceptions=False)

    def test_sign_up_with_a_weak_password_returns_400(self):
        response = self.client.post(
            "/user", json={"email": sample_user.email, "password": "weak"}
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn(
            "Password does not meet the fallback password policy",
            response.json()["detail"],
        )

    def _sign_up_with_main_api_error(
        self, status_code: int, body: bytes, content_type: str
    ):
        upstream = requests.Response()
        upstream.status_code = status_code
        upstream._content = body
        upstream.headers["Content-Type"] = content_type
        self.user_validation_service.get_user.side_effect = requests.HTTPError(
            f"{status_code} error", response=upstream
        )

        return self.client.post(
            "/user", json={"email": sample_user.email, "password": "Str0ng!Password"}
        )

    def test_a_json_error_from_the_main_api_is_passed_through(self):
        response = self._sign_up_with_main_api_error(
            401, b'{"errorMessages": ["Invalid credentials"]}', "application/json"
        )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"errorMessages": ["Invalid credentials"]})

    def test_a_non_json_server_error_from_the_main_api_returns_503(self):
        for status_code in [500, 502, 503, 504]:
            with self.subTest(status_code=status_code):
                response = self._sign_up_with_main_api_error(
                    status_code, b"<html><h1>502 Bad Gateway</h1></html>", "text/html"
                )

                self.assertEqual(response.status_code, 503)
                self.assertIn("detail", response.json())

    def test_a_non_json_client_error_from_the_main_api_keeps_its_status(self):
        response = self._sign_up_with_main_api_error(
            401, b"<html>Unauthorized</html>", "text/html"
        )

        self.assertEqual(response.status_code, 401)
        self.assertIn("detail", response.json())

    def test_a_non_json_error_with_an_unexpected_status_returns_502(self):
        response = self._sign_up_with_main_api_error(302, b"", "text/html")

        self.assertEqual(response.status_code, 502)
        self.assertIn("detail", response.json())
