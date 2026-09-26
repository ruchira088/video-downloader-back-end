import json
import unittest
from datetime import UTC, datetime
from pathlib import Path

from pydantic import ValidationError

from src.sync.messages import (
    RejectedOutcome,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
    ScheduledVideoUpsert,
    ScheduleRequest,
    parse_main_to_fallback_message,
    to_json,
)
from src.sync.timestamps import iso_millis

CONTRACT_DIRECTORY = Path(__file__).parent.parent.parent / "contract"


def _fixture(name: str) -> str:
    return (CONTRACT_DIRECTORY / name).read_text()


class TestTimestamps(unittest.TestCase):
    def test_iso_millis_formats_utc_with_milliseconds_and_z(self):
        value = datetime(2026, 9, 26, 8, 15, 30, 123456, tzinfo=UTC)

        self.assertEqual(iso_millis(value), "2026-09-26T08:15:30.123Z")

    def test_iso_millis_rejects_naive_datetimes(self):
        with self.assertRaises(ValueError):
            iso_millis(datetime(2026, 9, 26, 8, 15, 30))


class TestMessages(unittest.TestCase):
    def test_upsert_fixture_parses(self):
        message = parse_main_to_fallback_message(
            _fixture("scheduled-video-upsert.json")
        )

        assert isinstance(message, ScheduledVideoUpsert)
        self.assertEqual(message.video_id, "youtube-1a2b3c4d5e6f")
        self.assertEqual(message.user_ids, ["user-1", "user-2"])
        self.assertEqual(message.duration_ms, 212000)
        self.assertEqual(
            message.captured_at, datetime(2026, 9, 26, 8, 15, 30, 123000, tzinfo=UTC)
        )

    def test_removal_fixture_parses(self):
        message = parse_main_to_fallback_message(
            _fixture("scheduled-video-removal.json")
        )

        assert isinstance(message, ScheduledVideoRemoval)

    def test_request_resolved_fixtures_parse(self):
        scheduled = parse_main_to_fallback_message(
            _fixture("request-resolved-scheduled.json")
        )
        rejected = parse_main_to_fallback_message(
            _fixture("request-resolved-rejected.json")
        )

        assert isinstance(scheduled, RequestResolved)
        assert isinstance(scheduled.outcome, ScheduledOutcome)
        self.assertIsNone(scheduled.outcome.upsert.completed_at)
        assert isinstance(rejected, RequestResolved)
        assert isinstance(rejected.outcome, RejectedOutcome)
        self.assertEqual(rejected.outcome.reason, "Unsupported video site: example.com")

    def test_every_main_to_fallback_fixture_round_trips_exactly(self):
        for name in [
            "scheduled-video-upsert.json",
            "scheduled-video-removal.json",
            "request-resolved-scheduled.json",
            "request-resolved-rejected.json",
        ]:
            with self.subTest(name=name):
                message = parse_main_to_fallback_message(_fixture(name))

                self.assertEqual(
                    json.loads(to_json(message)), json.loads(_fixture(name))
                )

    def test_schedule_request_serialises_to_the_fixture(self):
        request = ScheduleRequest(
            request_id="4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
            user_id="user-1",
            url="https://www.youtube.com/watch?v=abc123",
            requested_at=datetime(2026, 9, 26, 8, 15, tzinfo=UTC),
        )

        self.assertEqual(
            json.loads(to_json(request)), json.loads(_fixture("schedule-request.json"))
        )

    def test_unknown_message_type_is_rejected(self):
        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message('{"type": "SomethingElse", "videoId": "v"}')

    def test_naive_timestamp_is_rejected(self):
        body = json.loads(_fixture("scheduled-video-removal.json"))
        body["capturedAt"] = "2026-09-26T08:20:00"

        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message(json.dumps(body))
