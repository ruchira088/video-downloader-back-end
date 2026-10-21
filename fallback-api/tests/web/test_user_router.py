import unittest
from unittest.mock import MagicMock, patch

import requests
from fastapi import FastAPI
from fastapi.testclient import TestClient
from moto import mock_aws

from src.services.deadline import SAFETY_MARGIN_SECONDS, Deadline
from src.services.user_service import CognitoUserService, UserUpsert
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.user_router import user_router
from tests.services.test_data_helpers import sample_user
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestUserRouter(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        self.cognito_client = cognito_details.cognito_client
        self.user_validation_service = MagicMock()
        self.user_validation_service.get_user.return_value = sample_user
        self.user_validation_service.check_credentials.return_value = sample_user.id

        user_service = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        )

        app = FastAPI()
        app.include_router(user_router(user_service))
        register_exception_handlers(app)
        self.client = TestClient(app, raise_server_exceptions=False)

    def test_sign_up_returns_201_then_200_once_the_user_exists(self):
        request = {"email": sample_user.email, "password": "Str0ng!Password"}

        created = self.client.post("/user", json=request)
        refreshed = self.client.post("/user", json=request)

        self.assertEqual(created.status_code, 201)
        self.assertEqual(refreshed.status_code, 200)
        self.assertEqual(refreshed.json(), created.json())
        self.assertEqual(created.json()["id"], sample_user.id)

    def test_sign_up_is_given_the_lambdas_remaining_time(self):
        # Mangum puts the Lambda context in the ASGI scope as aws.context
        lambda_context = MagicMock()
        lambda_context.get_remaining_time_in_millis.return_value = 9_000
        user_service = MagicMock()
        user_service.upsert_user.return_value = UserUpsert(sample_user, created=True)
        app = FastAPI()
        app.include_router(user_router(user_service))

        async def lambda_app(scope, receive, send):
            await app({**scope, "aws.context": lambda_context}, receive, send)

        response = TestClient(lambda_app).post(
            "/user", json={"email": sample_user.email, "password": "Str0ng!Password"}
        )

        self.assertEqual(response.status_code, 201)
        deadline = user_service.upsert_user.call_args.kwargs["deadline"]
        self.assertIsInstance(deadline, Deadline)
        self.assertAlmostEqual(deadline.remaining(), 9 - SAFETY_MARGIN_SECONDS, delta=1)

    def test_sign_up_with_a_weak_password_returns_400(self):
        response = self.client.post(
            "/user", json={"email": sample_user.email, "password": "weak"}
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn(
            "Password does not meet the fallback password policy",
            response.json()["detail"],
        )

    def test_sign_up_with_a_password_cognito_rejects_as_an_invalid_parameter_returns_400(
        self,
    ):
        cognito_client = self.cognito_client
        error = cognito_client.exceptions.InvalidParameterException(
            {"Error": {"Code": "InvalidParameterException", "Message": "x"}},
            "AdminSetUserPassword",
        )

        with patch.object(cognito_client, "admin_set_user_password", side_effect=error):
            response = self.client.post(
                "/user",
                json={"email": sample_user.email, "password": "Str0ng!Password "},
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
