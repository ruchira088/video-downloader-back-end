import logging
import random
import time
from collections.abc import Callable, Mapping
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any

from src.sync.items import (
    DELETED_STATUS,
    REJECTED_TTL,
    epoch_seconds,
    link_item,
    link_key,
    live_link_keys,
    pending_key,
    tombstone_item,
    video_item,
    video_key,
)
from src.sync.messages import (
    MainToFallbackMessage,
    RejectedOutcome,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
    ScheduledVideoUpsert,
)
from src.sync.timestamps import iso_micros, parse_iso_micros

logger = logging.getLogger(__name__)

MAX_TRANSACTION_WRITES = 100
# How far ahead of this Lambda's clock a capturedAt may be. A message stamped further in the
# future would win the capturedAt guard against every correct message until real time caught up.
MAX_CLOCK_SKEW = timedelta(minutes=5)

Write = dict[str, Any]
BuildWrites = Callable[[Mapping[str, Any] | None], tuple[list[Write], dict[str, Any]]]


class ApplyResult(StrEnum):
    APPLIED = "Applied"
    SKIPPED = "Skipped"


class TooManyWritesError(Exception):
    pass


class ConcurrentUpdateError(Exception):
    pass


class FutureCapturedAtError(Exception):
    pass


def _utc_now() -> datetime:
    return datetime.now(UTC)


class SyncApplier:
    MAX_ATTEMPTS = 3
    MAX_RETRY_JITTER_SECONDS = 0.1

    def __init__(
        self,
        table: Any,
        clock: Callable[[], datetime] = _utc_now,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self._table = table
        self._table_name: str = table.name
        self._client = table.meta.client
        self._clock = clock
        self._sleep = sleep

    def apply(self, message: MainToFallbackMessage) -> ApplyResult:
        match message:
            case ScheduledVideoUpsert():
                return self._apply_upsert(message, [])
            case ScheduledVideoRemoval():
                return self._apply_removal(message.video_id, message.captured_at, [])
            case RequestResolved(outcome=ScheduledOutcome() as outcome):
                resolve = [
                    self._delete(pending_key(message.user_id, message.request_id))
                ]
                return self._apply_upsert(outcome.upsert, resolve)
            case RequestResolved(outcome=RejectedOutcome() as outcome):
                self._reject_pending(
                    message.user_id, message.request_id, outcome.reason
                )
                return ApplyResult.APPLIED

        raise TypeError(f"Unsupported sync message: {type(message).__name__}")

    def _apply_upsert(
        self, upsert: ScheduledVideoUpsert, extra_writes: list[Write]
    ) -> ApplyResult:
        if upsert.status == DELETED_STATUS:
            return self._apply_removal(
                upsert.video_id, upsert.captured_at, extra_writes
            )

        new_users = frozenset(upsert.user_ids)
        scheduled_at = iso_micros(upsert.scheduled_at)
        new_keys = {
            _key_tuple(link_key(user_id, scheduled_at, upsert.video_id))
            for user_id in new_users
        }

        def build(
            current: Mapping[str, Any] | None,
        ) -> tuple[list[Write], dict[str, Any]]:
            writes = [
                self._put(link_item(user_id, upsert)) for user_id in sorted(new_users)
            ]
            writes += [
                self._delete(key)
                for key in live_link_keys(current, upsert.video_id)
                if _key_tuple(key) not in new_keys
            ]
            return writes, video_item(upsert)

        return self._apply_video_change(
            upsert.video_id, upsert.captured_at, build, extra_writes
        )

    def _apply_removal(
        self, video_id: str, captured_at: datetime, extra_writes: list[Write]
    ) -> ApplyResult:
        def build(
            current: Mapping[str, Any] | None,
        ) -> tuple[list[Write], dict[str, Any]]:
            writes = [self._delete(key) for key in live_link_keys(current, video_id)]
            return writes, tombstone_item(video_id, captured_at, self._clock())

        return self._apply_video_change(video_id, captured_at, build, extra_writes)

    def _apply_video_change(
        self,
        video_id: str,
        captured_at: datetime,
        build: BuildWrites,
        extra_writes: list[Write],
    ) -> ApplyResult:
        if captured_at > self._clock() + MAX_CLOCK_SKEW:
            # Fail the record, so it is retried and then dead-lettered, which raises the alarm.
            raise FutureCapturedAtError(
                f"capturedAt {iso_micros(captured_at)} of video {video_id} is more than "
                f"{MAX_CLOCK_SKEW} ahead of this clock"
            )

        for attempt in range(self.MAX_ATTEMPTS):
            if attempt > 0:
                # Spread out retries, so invocations racing on one video don't collide again.
                self._sleep(random.uniform(0, self.MAX_RETRY_JITTER_SECONDS))

            current = self._table.get_item(
                Key=video_key(video_id), ConsistentRead=True
            ).get("Item")

            stored_captured_at = _stored_captured_at(current)
            if stored_captured_at is not None and stored_captured_at >= captured_at:
                logger.warning(
                    "Skipping stale message for video %s: stored capturedAt %s, incoming %s",
                    video_id,
                    iso_micros(stored_captured_at),
                    iso_micros(captured_at),
                )
                # Stale message: keep the stored state, but still resolve any pending request.
                if extra_writes:
                    self._transact(extra_writes)
                return ApplyResult.SKIPPED

            writes, new_video_item = build(current)
            video_put = self._put(new_video_item)
            if current is None:
                video_put["Put"]["ConditionExpression"] = "attribute_not_exists(PK)"
            elif "capturedAt" not in current:
                video_put["Put"]["ConditionExpression"] = (
                    "attribute_exists(PK) AND attribute_not_exists(capturedAt)"
                )
            else:
                # The raw stored value, even when unparseable: the put must still lose to any
                # write that landed since this read.
                video_put["Put"]["ConditionExpression"] = "capturedAt = :expected"
                video_put["Put"]["ExpressionAttributeValues"] = {
                    ":expected": current["capturedAt"]
                }

            try:
                self._transact([video_put, *writes, *extra_writes])
                return ApplyResult.APPLIED
            except self._client.exceptions.TransactionCanceledException as error:
                if not _lost_a_race(error):
                    raise
                # Another invocation changed the video since we read it, or was writing one of
                # the same items at the same moment: re-read and retry.

        raise ConcurrentUpdateError(video_id)

    def _reject_pending(self, user_id: str, request_id: str, reason: str) -> None:
        try:
            self._table.update_item(
                Key=pending_key(user_id, request_id),
                UpdateExpression="SET #status = :rejected, #reason = :reason, #ttl = :ttl",
                ConditionExpression="attribute_exists(PK)",
                ExpressionAttributeNames={
                    "#status": "status",
                    "#reason": "reason",
                    "#ttl": "ttl",
                },
                ExpressionAttributeValues={
                    ":rejected": "Rejected",
                    ":reason": reason,
                    ":ttl": epoch_seconds(self._clock() + REJECTED_TTL),
                },
            )
        except self._client.exceptions.ConditionalCheckFailedException:
            logger.info("No pending item for rejected request %s", request_id)

    def _transact(self, writes: list[Write]) -> None:
        if len(writes) > MAX_TRANSACTION_WRITES:
            raise TooManyWritesError(
                f"{len(writes)} writes exceed the DynamoDB transaction limit"
            )

        self._client.transact_write_items(TransactItems=writes)

    def _put(self, item: dict[str, Any]) -> Write:
        return {"Put": {"TableName": self._table_name, "Item": item}}

    def _delete(self, key: dict[str, str]) -> Write:
        return {"Delete": {"TableName": self._table_name, "Key": key}}


def _stored_captured_at(current: Mapping[str, Any] | None) -> datetime | None:
    """The stored item's capturedAt, or None when there is none that can be compared.

    An unparseable value counts as absent, so the next message repairs the item instead of every
    message failing on it forever.
    """
    if current is None:
        return None

    raw = current.get("capturedAt")
    try:
        return parse_iso_micros(raw) if isinstance(raw, str) else None
    except ValueError:
        logger.warning(
            "Stored capturedAt %r of %s is unparseable; overwriting it",
            raw,
            current["PK"],
        )
        return None


_RACE_CANCELLATION_CODES = frozenset({"ConditionalCheckFailed", "TransactionConflict"})


def _lost_a_race(error: Exception) -> bool:
    reasons = getattr(error, "response", {}).get("CancellationReasons", [])
    return any(reason.get("Code") in _RACE_CANCELLATION_CODES for reason in reasons)


def _key_tuple(key: dict[str, str]) -> tuple[str, str]:
    return key["PK"], key["SK"]
