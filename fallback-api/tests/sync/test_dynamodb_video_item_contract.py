import json
import unittest
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

from moto import mock_aws

from src.sync.items import video_key
from src.sync.messages import parse_main_to_fallback_message
from src.sync.sync_applier import SyncApplier
from tests.services.test_service_helpers import setup_dynamodb

CONTRACT_DIRECTORY = Path(__file__).parent.parent.parent / "contract"


def _plain(value: Any) -> Any:
    """DynamoDB's Decimals as JSON numbers, so the item compares equal to parsed JSON."""
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, dict):
        return {name: _plain(item) for name, item in value.items()}
    if isinstance(value, list | set):
        return [_plain(item) for item in value]
    return value


@mock_aws
class TestDynamoDbVideoItemContract(unittest.TestCase):
    def test_applying_the_upsert_fixture_stores_exactly_the_video_item_fixture(self):
        """The main side's reconcile reads this item's attributes, so pin them down."""
        table = setup_dynamodb("scheduled-videos")
        applier = SyncApplier(table, clock=lambda: datetime(2026, 9, 26, 9, tzinfo=UTC))
        upsert = parse_main_to_fallback_message(
            (CONTRACT_DIRECTORY / "scheduled-video-upsert.json").read_text()
        )

        applier.apply(upsert)

        stored = table.get_item(Key=video_key("youtube-1a2b3c4d5e6f"))["Item"]
        expected = json.loads(
            (CONTRACT_DIRECTORY / "dynamodb-video-item.json").read_text()
        )
        self.assertEqual(_plain(stored), expected)
