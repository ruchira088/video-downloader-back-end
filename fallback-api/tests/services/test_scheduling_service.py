import json
import unittest
from base64 import urlsafe_b64encode
from datetime import timedelta
from typing import Any
from unittest.mock import patch

from botocore.exceptions import ClientError
from moto import mock_aws

from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.user import Role, User
from src.services.scheduling_service import (
    MAX_PENDING_REQUESTS,
    MAX_URL_LENGTH,
    PAGE_SIZE,
    DynamoDbSchedulingService,
)
from src.sync.items import (
    ALL_VIDEOS_PARTITION,
    VIDEO_SORT_KEY,
    epoch_seconds,
    pending_key,
    user_partition,
)
from src.sync.messages import ScheduledVideoRemoval
from src.sync.page_tokens import encode_page_token
from src.sync.sync_applier import SyncApplier
from src.sync.timestamps import iso_micros
from tests.services.test_service_helpers import setup_dynamodb, setup_sqs
from tests.sync.sync_test_data import FIXED_NOW, T0, sample_upsert

USER = User(id="user-1", email="u1@ruchij.com", first_name="U", last_name="One")
OTHER = User(id="user-2", email="u2@ruchij.com", first_name="U", last_name="Two")
ADMIN = User(
    id="admin-1", email="a@ruchij.com", first_name="A", last_name="D", role=Role.ADMIN
)


@mock_aws
class TestDynamoDbSchedulingService(unittest.TestCase):
    def setUp(self):
        self.table = setup_dynamodb("scheduled-videos")
        self.sqs_client, self.queue_url = setup_sqs("fallback-to-main")
        self.applier = SyncApplier(self.table, clock=lambda: FIXED_NOW)
        self.service = DynamoDbSchedulingService(
            self.table,
            self.sqs_client,
            self.queue_url,
            clock=lambda: FIXED_NOW,
            request_id_generator=lambda: "request-1",
        )

    def _video(
        self, video_id: str, user_ids: list[str], minutes: int, status: str = "Queued"
    ):
        self.applier.apply(
            sample_upsert(
                video_id=video_id,
                user_ids=user_ids,
                status=status,
                scheduled_at=T0 + timedelta(minutes=minutes),
            )
        )

    def _queued_bodies(self) -> list[dict]:
        response = self.sqs_client.receive_message(
            QueueUrl=self.queue_url, MaxNumberOfMessages=10
        )
        return [json.loads(message["Body"]) for message in response.get("Messages", [])]

    def test_schedule_queues_a_request_and_stores_a_pending_item(self):
        request_id = self.service.schedule(
            "  https://www.youtube.com/watch?v=abc ", USER
        )

        self.assertEqual(request_id, "request-1")
        self.assertEqual(
            self._queued_bodies(),
            [
                {
                    "type": "ScheduleRequest",
                    "requestId": "request-1",
                    "userId": "user-1",
                    "url": "https://www.youtube.com/watch?v=abc",
                    "requestedAt": "2026-09-26T08:00:00.000000Z",
                }
            ],
        )
        pending = self.table.get_item(Key=pending_key("user-1", "request-1"))["Item"]
        self.assertEqual(pending["status"], "Pending")
        self.assertEqual(pending["ttl"], epoch_seconds(FIXED_NOW + timedelta(days=14)))

    def test_schedule_rejects_invalid_urls_without_queueing(self):
        for url in [
            "",
            "not a url",
            "ftp://example.com/video",
            "https://",
            "https://a b.com",
        ]:
            with self.subTest(url=url), self.assertRaises(InvalidUrlException):
                self.service.schedule(url, USER)

        self.assertEqual(self._queued_bodies(), [])

    def test_schedule_raises_service_unavailable_when_sqs_fails_and_writes_nothing(
        self,
    ):
        service = DynamoDbSchedulingService(
            self.table,
            self.sqs_client,
            self.queue_url + "-missing",
            clock=lambda: FIXED_NOW,
            request_id_generator=lambda: "request-1",
        )

        with self.assertRaises(ServiceUnavailableException):
            service.schedule("https://www.youtube.com/watch?v=abc", USER)

        self.assertNotIn(
            "Item", self.table.get_item(Key=pending_key("user-1", "request-1"))
        )

    def test_schedule_still_succeeds_when_the_pending_write_fails_after_queueing(self):
        error = ClientError(
            {"Error": {"Code": "ProvisionedThroughputExceededException"}}, "PutItem"
        )

        with (
            patch.object(self.table, "put_item", side_effect=error),
            self.assertLogs("src.services.scheduling_service", "ERROR") as logs,
        ):
            request_id = self.service.schedule(
                "https://www.youtube.com/watch?v=abc", USER
            )

        self.assertEqual(request_id, "request-1")
        self.assertEqual(len(self._queued_bodies()), 1)
        self.assertIn("request-1", logs.output[0])

    def test_schedule_accepts_a_url_of_up_to_2048_characters(self):
        prefix = "https://www.youtube.com/watch?v="
        url = prefix + "a" * (MAX_URL_LENGTH - len(prefix))

        self.assertEqual(self.service.schedule(url, USER), "request-1")

    def test_schedule_rejects_a_url_longer_than_2048_characters(self):
        prefix = "https://www.youtube.com/watch?v="
        url = prefix + "a" * (MAX_URL_LENGTH + 1 - len(prefix))

        with self.assertRaises(InvalidUrlException):
            self.service.schedule(url, USER)

        self.assertEqual(self._queued_bodies(), [])

    def test_new_user_gets_an_empty_listing(self):
        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual(
            (listing.videos, listing.pending, listing.next_page_token), ([], [], None)
        )

    def test_user_sees_only_their_videos_newest_first_and_their_pending_requests(self):
        self._video("old", ["user-1"], minutes=1)
        self._video("new", ["user-1", "user-2"], minutes=2)
        self._video("others", ["user-2"], minutes=3)
        self.service.schedule("https://www.youtube.com/watch?v=pending", USER)

        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual([v.video_id for v in listing.videos], ["new", "old"])
        self.assertEqual([p.request_id for p in listing.pending], ["request-1"])

    def test_admin_sees_all_live_videos_but_only_their_own_pending_requests(self):
        self._video("a", ["user-1"], minutes=1)
        self._video("b", ["user-2"], minutes=2)
        self._video("gone", ["user-2"], minutes=3)
        self.applier.apply(
            ScheduledVideoRemoval(video_id="gone", captured_at=T0 + timedelta(hours=1))
        )
        self.service.schedule("https://www.youtube.com/watch?v=x", OTHER)

        listing = self.service.list_schedules(ADMIN, None, None)

        self.assertEqual([v.video_id for v in listing.videos], ["b", "a"])
        self.assertEqual(listing.pending, [])

    def _put_pending(self, request_id: str, minutes: int, ttl: int | None) -> None:
        item: dict[str, Any] = {
            **pending_key("user-1", request_id),
            "requestId": request_id,
            "url": "https://www.youtube.com/watch?v=abc",
            "requestedAt": iso_micros(T0 + timedelta(minutes=minutes)),
            "status": "Rejected",
        }
        if ttl is not None:
            item["ttl"] = ttl
        self.table.put_item(Item=item)

    def test_pending_items_past_their_ttl_are_not_listed(self):
        now = epoch_seconds(FIXED_NOW)
        self._put_pending("expired", minutes=1, ttl=now - 1)
        self._put_pending("expiring-now", minutes=2, ttl=now)
        self._put_pending("live", minutes=3, ttl=now + 1)
        self._put_pending("no-ttl", minutes=4, ttl=None)

        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual([p.request_id for p in listing.pending], ["no-ttl", "live"])

    def test_only_the_newest_pending_requests_are_listed(self):
        for index in range(MAX_PENDING_REQUESTS + 5):
            self._put_pending(f"request-{index:03d}", minutes=index, ttl=None)

        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual(len(listing.pending), MAX_PENDING_REQUESTS)
        self.assertEqual(
            listing.pending[0].request_id, f"request-{MAX_PENDING_REQUESTS + 4:03d}"
        )
        self.assertEqual(listing.pending[-1].request_id, "request-005")

    def test_the_newest_pending_requests_are_chosen_by_requested_at_not_sort_key(self):
        # Request ids are random UUIDs, so PENDING#<requestId> sort keys aren't in time order:
        # here the newest requests have the smallest sort keys.
        count = MAX_PENDING_REQUESTS + 5
        for index in range(count):
            self._put_pending(f"request-{count - index:03d}", minutes=index, ttl=None)

        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual(len(listing.pending), MAX_PENDING_REQUESTS)
        self.assertEqual(listing.pending[0].request_id, "request-001")
        self.assertEqual(
            listing.pending[-1].request_id, f"request-{MAX_PENDING_REQUESTS:03d}"
        )

    def test_status_filter(self):
        self._video("queued", ["user-1"], minutes=1)
        self._video("done", ["user-1"], minutes=2, status="Completed")

        listing = self.service.list_schedules(USER, "Completed", None)

        self.assertEqual([v.video_id for v in listing.videos], ["done"])

    def test_pagination(self):
        for index in range(PAGE_SIZE + 5):
            self._video(f"video-{index:02d}", ["user-1"], minutes=index)

        first = self.service.list_schedules(USER, None, None)
        assert first.next_page_token is not None
        second = self.service.list_schedules(USER, None, first.next_page_token)

        self.assertEqual(len(first.videos), PAGE_SIZE)
        self.assertEqual(len(second.videos), 5)
        self.assertIsNone(second.next_page_token)
        self.assertEqual(second.videos[-1].video_id, "video-00")

    def test_admin_pagination_round_trip(self):
        for index in range(PAGE_SIZE + 5):
            self._video(f"video-{index:02d}", ["user-1"], minutes=index)

        first = self.service.list_schedules(ADMIN, None, None)
        assert first.next_page_token is not None
        second = self.service.list_schedules(ADMIN, None, first.next_page_token)

        self.assertEqual(len(first.videos), PAGE_SIZE)
        self.assertEqual(len(second.videos), 5)
        self.assertIsNone(second.next_page_token)
        self.assertEqual(second.videos[-1].video_id, "video-00")

    def test_malformed_page_token_is_rejected(self):
        for token in ["!!!", "bm90LWpzb24=", encode_page_token({"PK": 1})]:
            with (
                self.subTest(token=token),
                self.assertRaises(InvalidPageTokenException),
            ):
                self.service.list_schedules(USER, None, token)

    def test_page_token_from_another_users_partition_is_rejected(self):
        token = encode_page_token({"PK": "USER#user-2", "SK": "VIDEO#2026#v"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(USER, None, token)

    def test_user_page_token_is_rejected_for_the_admin_index(self):
        token = encode_page_token({"PK": "USER#admin-1", "SK": "VIDEO#2026#v"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(ADMIN, None, token)

    def test_user_page_token_with_an_extra_key_is_rejected(self):
        token = encode_page_token(
            {"PK": user_partition("user-1"), "SK": "VIDEO#x", "extra": "y"}
        )

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(USER, None, token)

    def test_user_page_token_with_a_pending_sort_key_is_rejected(self):
        token = encode_page_token({"PK": user_partition("user-1"), "SK": "PENDING#abc"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(USER, None, token)

    def test_admin_page_token_missing_keys_is_rejected(self):
        token = encode_page_token({"GSI1PK": "VIDEO"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(ADMIN, None, token)

    def test_admin_page_token_with_an_empty_key_value_is_rejected(self):
        token = encode_page_token(
            {
                "PK": "VIDEO#youtube-abc",
                "SK": VIDEO_SORT_KEY,
                "GSI1PK": ALL_VIDEOS_PARTITION,
                "GSI1SK": "",
            }
        )

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(ADMIN, None, token)

    def test_page_token_with_a_lone_surrogate_is_rejected(self):
        tokens = [
            (USER, b'{"PK":"USER#user-1","SK":"VIDEO#\\ud800"}'),
            (
                ADMIN,
                b'{"PK":"VIDEO#\\ud800","SK":"VIDEO","GSI1PK":"VIDEO","GSI1SK":"x"}',
            ),
        ]

        for user, raw_token in tokens:
            token = urlsafe_b64encode(raw_token).decode()
            with (
                self.subTest(user=user.id),
                self.assertRaises(InvalidPageTokenException),
            ):
                self.service.list_schedules(user, None, token)

    def test_user_page_token_with_an_oversized_key_value_is_rejected(self):
        token = encode_page_token(
            {"PK": user_partition("user-1"), "SK": "VIDEO#" + "x" * 2000}
        )

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(USER, None, token)
