import json
import unittest
from datetime import UTC, datetime, timedelta, timezone
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
from src.sync.timestamps import iso_micros, require_iso_micros

CONTRACT_DIRECTORY = Path(__file__).parent.parent.parent / "contract"


def _fixture(name: str) -> str:
    return (CONTRACT_DIRECTORY / name).read_text()


class TestTimestamps(unittest.TestCase):
    def test_iso_micros_formats_utc_with_microseconds_and_z(self):
        value = datetime(2026, 9, 26, 8, 15, 30, 123456, tzinfo=UTC)

        self.assertEqual(iso_micros(value), "2026-09-26T08:15:30.123456Z")

    def test_iso_micros_always_writes_six_fractional_digits(self):
        self.assertEqual(
            iso_micros(datetime(2026, 9, 26, 8, 15, 30, tzinfo=UTC)),
            "2026-09-26T08:15:30.000000Z",
        )

    def test_iso_micros_converts_other_offsets_to_utc(self):
        value = datetime(
            2026, 9, 26, 18, 15, 30, 42, tzinfo=timezone(timedelta(hours=10))
        )

        self.assertEqual(iso_micros(value), "2026-09-26T08:15:30.000042Z")

    def test_iso_micros_rejects_naive_datetimes(self):
        with self.assertRaises(ValueError):
            iso_micros(datetime(2026, 9, 26, 8, 15, 30))

    def test_require_iso_micros_rejects_non_ascii_digits(self):
        # Arabic-Indic and fullwidth digits, which a Unicode \d would match.
        for value in [
            "٢026-09-26T08:20:00.000000Z",
            "2026-09-26T08:20:00.00000０Z",
        ]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    require_iso_micros(value)


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
            message.captured_at, datetime(2026, 9, 26, 8, 15, 30, 123456, tzinfo=UTC)
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
            requested_at=datetime(2026, 9, 26, 8, 15, 0, 654321, tzinfo=UTC),
        )

        self.assertEqual(
            json.loads(to_json(request)), json.loads(_fixture("schedule-request.json"))
        )

    def test_unknown_message_type_is_rejected(self):
        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message('{"type": "SomethingElse", "videoId": "v"}')

    def test_timestamps_not_in_the_fixed_width_microsecond_format_are_rejected(self):
        for captured_at in [
            "2026-09-26T08:20:00.000Z",
            "2026-09-26T08:20:00Z",
            "2026-09-26T08:20:00.000000+00:00",
            "2026-09-26T08:20:00.0000000Z",
            "2026-09-26 08:20:00.000000Z",
        ]:
            body = json.loads(_fixture("scheduled-video-removal.json"))
            body["capturedAt"] = captured_at

            with self.subTest(captured_at=captured_at):
                with self.assertRaises(ValidationError):
                    parse_main_to_fallback_message(json.dumps(body))

    def test_contract_fixtures_carry_non_zero_microseconds(self):
        upsert = parse_main_to_fallback_message(_fixture("scheduled-video-upsert.json"))

        assert isinstance(upsert, ScheduledVideoUpsert)
        assert upsert.completed_at is not None
        self.assertEqual(upsert.completed_at.microsecond, 500250)

    def test_naive_timestamp_is_rejected(self):
        body = json.loads(_fixture("scheduled-video-removal.json"))
        body["capturedAt"] = "2026-09-26T08:20:00"

        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message(json.dumps(body))
