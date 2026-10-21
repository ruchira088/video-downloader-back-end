import importlib
import sys
import unittest
from datetime import UTC, datetime
from unittest.mock import MagicMock

import pytest
from moto import mock_aws

from src.services.user_service import CognitoUserService
from src.sync.messages import UserRevocation, to_json
from tests.services.test_data_helpers import sample_password, sample_user
from tests.services.test_service_helpers import setup_cognito, setup_dynamodb


@mock_aws
class TestSyncHandler(unittest.TestCase):
    @pytest.fixture(autouse=True)
    def _environment(self, monkeypatch):
        self.monkeypatch = monkeypatch
        monkeypatch.delenv("AWS_COGNITO_USER_POOL_ID", raising=False)
        monkeypatch.setenv("SCHEDULED_VIDEOS_TABLE_NAME", "scheduled-videos")
        monkeypatch.setenv(
            "FALLBACK_TO_MAIN_QUEUE_URL", "https://sqs.example.com/queue"
        )

    def test_handler_processes_an_empty_batch(self):
        setup_dynamodb("scheduled-videos")
        sys.modules.pop("sync_handler", None)
        sync_handler = importlib.import_module("sync_handler")

        self.assertEqual(
            sync_handler.handler({"Records": []}, None), {"batchItemFailures": []}
        )

    def test_handler_revokes_the_fallback_account_of_a_revoked_user(self):
        setup_dynamodb("scheduled-videos")
        cognito_details = setup_cognito(__name__)
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        ).upsert_user(email=sample_user.email, password=sample_password)
        self.monkeypatch.setenv(
            "AWS_COGNITO_USER_POOL_ID", cognito_details.user_pool_id
        )
        sys.modules.pop("sync_handler", None)
        sync_handler = importlib.import_module("sync_handler")
        revocation = UserRevocation(
            user_id=sample_user.id,
            email=sample_user.email,
            captured_at=datetime.now(UTC),
        )

        response = sync_handler.handler(
            {"Records": [{"messageId": "m1", "body": to_json(revocation)}]}, None
        )

        self.assertEqual(response, {"batchItemFailures": []})
        with self.assertRaises(
            cognito_details.cognito_client.exceptions.UserNotFoundException
        ):
            cognito_details.cognito_client.admin_get_user(
                UserPoolId=cognito_details.user_pool_id, Username=sample_user.email
            )
