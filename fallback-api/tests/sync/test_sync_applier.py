import unittest
from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from boto3.dynamodb.conditions import Key
from moto import mock_aws

from src.sync.items import epoch_seconds, pending_key, video_key
from src.sync.messages import (
    RejectedOutcome,
    to_json,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
)
from src.sync.sqs_batch import process_sqs_batch
from src.sync.sync_applier import (
    ApplyResult,
    ConcurrentUpdateError,
    FutureCapturedAtError,
    SyncApplier,
    TooManyWritesError,
)
from tests.services.test_service_helpers import setup_dynamodb
from tests.sync.sync_test_data import FIXED_NOW, T0, later, sample_upsert

SCHEDULED_AT = "2026-09-25T21:04:11.000000Z"


@mock_aws
class TestSyncApplier(unittest.TestCase):
    def setUp(self):
        self.table = setup_dynamodb("scheduled-videos")
        self.applier = SyncApplier(self.table, clock=lambda: FIXED_NOW)

    def _video(self, video_id: str = "youtube-abc") -> dict | None:
        return self.table.get_item(Key=video_key(video_id)).get("Item")

    def _links(self, user_id: str) -> list[dict]:
        return self.table.query(
            KeyConditionExpression=Key("PK").eq(f"USER#{user_id}")
            & Key("SK").begins_with("VIDEO#")
        )["Items"]

    def _put_pending(self, user_id: str, request_id: str) -> None:
        self.table.put_item(
            Item={
                **pending_key(user_id, request_id),
                "requestId": request_id,
                "url": "https://www.youtube.com/watch?v=abc",
                "requestedAt": "2026-09-26T06:59:00.000000Z",
                "status": "Pending",
            }
        )

    def test_new_upsert_writes_the_video_and_one_link_per_user(self):
        result = self.applier.apply(sample_upsert())

        self.assertEqual(result, ApplyResult.APPLIED)
        video = self._video()
        assert video is not None
        self.assertEqual(video["userIds"], ["user-1", "user-2"])
        self.assertEqual(video["capturedAt"], "2026-09-26T07:00:00.000000Z")
        self.assertEqual(video["GSI1PK"], "VIDEO")
        self.assertEqual(video["GSI1SK"], f"{SCHEDULED_AT}#youtube-abc")
        self.assertFalse(video["deleted"])
        for user_id in ["user-1", "user-2"]:
            links = self._links(user_id)
            self.assertEqual(
                [link["SK"] for link in links], [f"VIDEO#{SCHEDULED_AT}#youtube-abc"]
            )
            self.assertEqual(links[0]["title"], "Sample video")
            self.assertEqual(int(links[0]["durationMs"]), 212000)

    def test_equal_or_older_captured_at_is_skipped(self):
        self.applier.apply(sample_upsert(captured_at=later(5), title="Newer"))

        equal = self.applier.apply(sample_upsert(captured_at=later(5), title="Equal"))
        older = self.applier.apply(sample_upsert(captured_at=later(1), title="Older"))

        self.assertEqual((equal, older), (ApplyResult.SKIPPED, ApplyResult.SKIPPED))
        video = self._video()
        assert video is not None
        self.assertEqual(video["title"], "Newer")

    def test_removed_user_loses_their_link(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), user_ids=["user-1"]))

        self.assertEqual(len(self._links("user-1")), 1)
        self.assertEqual(self._links("user-2"), [])

    def test_upsert_with_no_users_keeps_the_video_without_links(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), user_ids=[]))

        video = self._video()
        assert video is not None
        self.assertEqual(video["userIds"], [])
        self.assertEqual(self._links("user-1"), [])

    def test_removal_deletes_links_and_leaves_a_tombstone(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(
            ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(1))
        )

        video = self._video()
        assert video is not None
        self.assertTrue(video["deleted"])
        self.assertNotIn("GSI1PK", video)
        self.assertEqual(
            int(video["ttl"]), epoch_seconds(FIXED_NOW + timedelta(days=15))
        )
        self.assertEqual(self._links("user-1"), [])

    def test_upsert_with_deleted_status_is_treated_as_removal(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), status="Deleted"))

        video = self._video()
        assert video is not None
        self.assertTrue(video["deleted"])
        self.assertEqual(self._links("user-2"), [])

    def test_late_upsert_does_not_resurrect_a_tombstone(self):
        self.applier.apply(
            ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(5))
        )

        result = self.applier.apply(sample_upsert(captured_at=later(1)))

        self.assertEqual(result, ApplyResult.SKIPPED)
        self.assertEqual(self._links("user-1"), [])

    def test_newer_upsert_after_a_tombstone_recreates_the_video(self):
        self.applier.apply(
            ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(1))
        )

        self.applier.apply(sample_upsert(captured_at=later(5)))

        video = self._video()
        assert video is not None
        self.assertFalse(video["deleted"])
        self.assertNotIn("ttl", video)
        self.assertEqual(len(self._links("user-1")), 1)

    def test_request_resolved_scheduled_replaces_the_pending_item(self):
        self._put_pending("user-1", "request-1")

        self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=ScheduledOutcome(upsert=sample_upsert(user_ids=["user-1"])),
            )
        )

        self.assertNotIn(
            "Item", self.table.get_item(Key=pending_key("user-1", "request-1"))
        )
        self.assertEqual(len(self._links("user-1")), 1)

    def test_request_resolved_scheduled_deletes_pending_even_when_the_upsert_is_stale(
        self,
    ):
        self.applier.apply(sample_upsert(captured_at=later(10)))
        self._put_pending("user-1", "request-1")

        result = self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=ScheduledOutcome(upsert=sample_upsert(captured_at=later(1))),
            )
        )

        self.assertEqual(result, ApplyResult.SKIPPED)
        self.assertNotIn(
            "Item", self.table.get_item(Key=pending_key("user-1", "request-1"))
        )

    def test_request_resolved_rejected_marks_the_pending_item(self):
        self._put_pending("user-1", "request-1")

        self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=RejectedOutcome(reason="Unsupported video site"),
            )
        )

        pending = self.table.get_item(Key=pending_key("user-1", "request-1"))["Item"]
        self.assertEqual(pending["status"], "Rejected")
        self.assertEqual(pending["reason"], "Unsupported video site")
        self.assertEqual(
            int(pending["ttl"]), epoch_seconds(FIXED_NOW + timedelta(days=7))
        )

    def test_rejection_for_a_missing_pending_item_creates_nothing(self):
        self.applier.apply(
            RequestResolved(
                request_id="missing",
                user_id="user-1",
                outcome=RejectedOutcome(reason="Unsupported video site"),
            )
        )

        self.assertNotIn(
            "Item", self.table.get_item(Key=pending_key("user-1", "missing"))
        )

    def test_rescheduled_video_replaces_the_users_old_link(self):
        self.applier.apply(sample_upsert())
        new_scheduled_at = datetime(2026, 9, 26, 10, 0, tzinfo=UTC)

        self.applier.apply(
            sample_upsert(captured_at=later(1), scheduled_at=new_scheduled_at)
        )

        links = self._links("user-1")
        self.assertEqual(len(links), 1)
        self.assertEqual(
            links[0]["SK"], "VIDEO#2026-09-26T10:00:00.000000Z#youtube-abc"
        )

    def test_stale_removal_after_reschedule_does_not_leave_a_duplicate_link(self):
        self.applier.apply(sample_upsert())
        new_scheduled_at = datetime(2026, 9, 26, 10, 0, tzinfo=UTC)

        applied = self.applier.apply(
            sample_upsert(captured_at=later(5), scheduled_at=new_scheduled_at)
        )
        skipped = self.applier.apply(
            ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(1))
        )

        self.assertEqual(applied, ApplyResult.APPLIED)
        self.assertEqual(skipped, ApplyResult.SKIPPED)
        for user_id in ["user-1", "user-2"]:
            links = self._links(user_id)
            self.assertEqual(len(links), 1)
            self.assertEqual(
                links[0]["SK"], "VIDEO#2026-09-26T10:00:00.000000Z#youtube-abc"
            )

    def test_added_user_gets_a_link_without_disturbing_others(self):
        self.applier.apply(sample_upsert(user_ids=["user-1"]))

        self.applier.apply(
            sample_upsert(captured_at=later(1), user_ids=["user-1", "user-2"])
        )

        self.assertEqual(len(self._links("user-1")), 1)
        self.assertEqual(len(self._links("user-2")), 1)

    def test_more_writes_than_a_transaction_allows_raises(self):
        user_ids = [f"user-{index}" for index in range(100)]

        with self.assertRaises(TooManyWritesError):
            self.applier.apply(sample_upsert(user_ids=user_ids, captured_at=T0))

    def test_retries_and_succeeds_after_a_concurrent_write_is_detected(self):
        self.applier.apply(sample_upsert(captured_at=T0))
        stale_current = self.table.get_item(Key=video_key("youtube-abc")).get("Item")

        # A second invocation applies a newer message while we're mid-retry.
        self.applier.apply(sample_upsert(captured_at=later(5), title="Concurrent"))

        real_get_item = self.applier._table.get_item
        calls = {"count": 0}

        def get_item_side_effect(**kwargs):
            calls["count"] += 1
            if calls["count"] == 1:
                return {"Item": stale_current}
            return real_get_item(**kwargs)

        with patch.object(
            self.applier._table, "get_item", side_effect=get_item_side_effect
        ) as mock_get_item:
            result = self.applier.apply(
                sample_upsert(captured_at=later(10), title="Final")
            )

        self.assertEqual(mock_get_item.call_count, 2)
        self.assertEqual(result, ApplyResult.APPLIED)
        video = self._video()
        assert video is not None
        self.assertEqual(video["title"], "Final")

    def test_always_stale_reads_raise_concurrent_update_error(self):
        self.applier.apply(sample_upsert(captured_at=T0))
        stale_current = self.table.get_item(Key=video_key("youtube-abc")).get("Item")

        # Advance the real stored state so the patched-in stale read never matches it.
        self.applier.apply(sample_upsert(captured_at=later(1), title="Real"))

        with patch.object(
            self.applier._table, "get_item", return_value={"Item": stale_current}
        ) as mock_get_item:
            with self.assertRaises(ConcurrentUpdateError):
                self.applier.apply(
                    sample_upsert(captured_at=later(5), title="Never applied")
                )

        self.assertEqual(mock_get_item.call_count, SyncApplier.MAX_ATTEMPTS)

    def test_cancellation_for_a_reason_other_than_condition_check_is_reraised(self):
        self.applier.apply(sample_upsert(captured_at=T0))

        exception_type = self.applier._client.exceptions.TransactionCanceledException
        error = exception_type(
            {
                "Error": {
                    "Code": "TransactionCanceledException",
                    "Message": "Cancelled",
                },
                "CancellationReasons": [{"Code": "ThrottlingError"}],
            },
            "TransactWriteItems",
        )

        with patch.object(
            self.applier._client, "transact_write_items", side_effect=error
        ) as mock_transact:
            with self.assertRaises(exception_type):
                self.applier.apply(
                    sample_upsert(captured_at=later(1), title="Never applied")
                )

        self.assertEqual(mock_transact.call_count, 1)

    def test_a_captured_at_more_than_five_minutes_ahead_of_the_clock_is_rejected(self):
        future = FIXED_NOW + timedelta(minutes=5, microseconds=1)

        for message in [
            sample_upsert(captured_at=future),
            ScheduledVideoRemoval(video_id="youtube-abc", captured_at=future),
        ]:
            with self.subTest(message=type(message).__name__):
                with self.assertRaises(FutureCapturedAtError):
                    self.applier.apply(message)

        self.assertIsNone(self._video())

    def test_a_captured_at_up_to_five_minutes_ahead_of_the_clock_is_applied(self):
        result = self.applier.apply(
            sample_upsert(captured_at=FIXED_NOW + timedelta(minutes=5))
        )

        self.assertEqual(result, ApplyResult.APPLIED)

    def test_a_future_captured_at_fails_its_record_in_the_batch(self):
        future = sample_upsert(captured_at=FIXED_NOW + timedelta(hours=1))

        response = process_sqs_batch(
            {"Records": [{"messageId": "future", "body": to_json(future)}]},
            self.applier,
        )

        self.assertEqual(
            response, {"batchItemFailures": [{"itemIdentifier": "future"}]}
        )

    def test_a_stale_message_is_logged_with_both_captured_at_values(self):
        self.applier.apply(sample_upsert(captured_at=later(5)))

        with self.assertLogs("src.sync.sync_applier", level="WARNING") as logs:
            self.applier.apply(sample_upsert(captured_at=later(1)))

        self.assertEqual(len(logs.records), 1)
        message = logs.records[0].getMessage()
        self.assertIn("youtube-abc", message)
        self.assertIn("2026-09-26T07:05:00.000000Z", message)
        self.assertIn("2026-09-26T07:01:00.000000Z", message)

    def test_an_unparseable_stored_captured_at_is_overwritten(self):
        self.applier.apply(sample_upsert(captured_at=later(5)))
        self.table.update_item(
            Key=video_key("youtube-abc"),
            UpdateExpression="SET capturedAt = :corrupt",
            ExpressionAttributeValues={":corrupt": "9999-corrupted"},
        )

        result = self.applier.apply(sample_upsert(captured_at=later(1), title="Fixed"))

        self.assertEqual(result, ApplyResult.APPLIED)
        video = self._video()
        assert video is not None
        self.assertEqual(video["title"], "Fixed")
        self.assertEqual(video["capturedAt"], "2026-09-26T07:01:00.000000Z")

    def test_an_unparseable_stored_captured_at_still_guards_concurrent_writes(self):
        self.applier.apply(sample_upsert(captured_at=later(5)))
        self.table.update_item(
            Key=video_key("youtube-abc"),
            UpdateExpression="SET capturedAt = :corrupt",
            ExpressionAttributeValues={":corrupt": "9999-corrupted"},
        )
        corrupted = self.table.get_item(Key=video_key("youtube-abc"))["Item"]
        # Another invocation repairs the item after we read the corrupted copy.
        self.applier.apply(sample_upsert(captured_at=later(10), title="Newest"))

        with patch.object(
            self.applier._table, "get_item", return_value={"Item": corrupted}
        ):
            with self.assertRaises(ConcurrentUpdateError):
                self.applier.apply(sample_upsert(captured_at=later(1), title="Old"))

        video = self._video()
        assert video is not None
        self.assertEqual(video["title"], "Newest")

    def test_a_stored_item_without_captured_at_is_overwritten(self):
        self.table.put_item(Item={**video_key("youtube-abc"), "videoId": "youtube-abc"})

        result = self.applier.apply(sample_upsert(captured_at=later(1)))

        self.assertEqual(result, ApplyResult.APPLIED)
