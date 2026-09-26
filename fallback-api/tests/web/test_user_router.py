import unittest
from unittest.mock import MagicMock

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
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        user_service = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        )

        app = FastAPI()
        app.include_router(user_router(user_service))
        register_exception_handlers(app)
        self.client = TestClient(app)

    def test_sign_up_with_a_weak_password_returns_400(self):
        response = self.client.post(
            "/user", json={"email": sample_user.email, "password": "weak"}
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn(
            "Password does not meet the fallback password policy",
            response.json()["detail"],
        )
