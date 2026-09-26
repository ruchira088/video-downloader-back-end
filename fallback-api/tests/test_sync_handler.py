import importlib
import sys
import unittest

import pytest
from moto import mock_aws

from tests.services.test_service_helpers import setup_dynamodb


@mock_aws
class TestSyncHandler(unittest.TestCase):
    @pytest.fixture(autouse=True)
    def _environment(self, monkeypatch):
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
