import logging
import random
import time
from collections.abc import Callable, Mapping
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any
from uuid import uuid4

from src.sync.items import (
    DELETED_STATUS,
    LOCK_ID,
    LOCKED_UNTIL,
    PENDING_LINK_KEYS,
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
MAX_BATCH_WRITES = 25
MAX_BATCH_ATTEMPTS = 5
# Must outlive the SyncFunction's 30 s timeout (template.yaml), so a lock only expires once its
# holder can no longer be writing.
LOCK_DURATION = timedelta(minutes=2)
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


class UnprocessedWritesError(Exception):
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

            if 1 + len(writes) + len(extra_writes) <= MAX_TRANSACTION_WRITES:
                applied = self._apply_in_transaction(
                    current, new_video_item, writes, extra_writes
                )
            else:
                applied = self._apply_in_batches(
                    current, new_video_item, writes, extra_writes
                )

            if applied:
                return ApplyResult.APPLIED
            # Another invocation changed or locked the video since we read it, or was writing
            # one of the same items at the same moment: re-read and retry.

        raise ConcurrentUpdateError(video_id)

    def _apply_in_transaction(
        self,
        current: Mapping[str, Any] | None,
        new_video_item: dict[str, Any],
        writes: list[Write],
        extra_writes: list[Write],
    ) -> bool:
        video_put = self._put(new_video_item)
        video_put["Put"].update(self._unchanged_and_unlocked(current))

        try:
            self._transact([video_put, *writes, *extra_writes])
            return True
        except self._client.exceptions.TransactionCanceledException as error:
            if not _lost_a_race(error):
                raise
            return False

    def _apply_in_batches(
        self,
        current: Mapping[str, Any] | None,
        new_video_item: dict[str, Any],
        writes: list[Write],
        extra_writes: list[Write],
    ) -> bool:
        """Apply a change with more link writes than one transaction can hold.

        Without a transaction, the link writes and the video put can interleave with another
        invocation's, and a newer message only cleans up the links recorded on the video item it
        reads -- so an older message's links, written after that read, would be left behind, or a
        newer message's links deleted. Instead:

        1. Lock the video item, conditional on it being unchanged since the read and not locked,
           and record every link key this apply may put in `pendingLinkKeys`. Every write to the
           video, in either path, is conditional on it being unlocked, so no other change can
           interleave.
        2. Write the links in idempotent batches.
        3. Put the video item, which drops the lock, in a transaction with any extra writes,
           conditional on still holding the lock.

        If this invocation dies part-way, its lock expires after LOCK_DURATION. Whoever applies
        the next change then treats `pendingLinkKeys` as live links, so any link no longer wanted
        is deleted.
        """
        lock_id = str(uuid4())
        put_keys = [
            [write["Put"]["Item"]["PK"], write["Put"]["Item"]["SK"]]
            for write in writes
            if "Put" in write
        ]
        previous_keys = [
            list(key) for key in (current or {}).get(PENDING_LINK_KEYS, [])
        ]
        pending_keys = sorted({tuple(key) for key in [*previous_keys, *put_keys]})
        condition = self._unchanged_and_unlocked(current)

        # A new video gets a placeholder item that holds the lock; it is marked deleted so the
        # listings and the main side's reconcile ignore it.
        placeholder = ", deleted = if_not_exists(deleted, :deleted)"
        try:
            self._table.update_item(
                Key=video_key(new_video_item["videoId"]),
                # A tombstone's ttl is removed, so DynamoDB can't delete the item, and with it
                # pendingLinkKeys, while it is locked. The final put sets the new item's ttl.
                UpdateExpression=f"SET {LOCK_ID} = :lockId, {LOCKED_UNTIL} = :lockedUntil, "
                f"{PENDING_LINK_KEYS} = :pendingLinkKeys{placeholder} REMOVE #ttl",
                ConditionExpression=condition["ConditionExpression"],
                ExpressionAttributeNames={"#ttl": "ttl"},
                ExpressionAttributeValues={
                    **condition["ExpressionAttributeValues"],
                    ":lockId": lock_id,
                    ":lockedUntil": epoch_seconds(self._clock() + LOCK_DURATION),
                    ":pendingLinkKeys": [list(key) for key in pending_keys],
                    ":deleted": True,
                },
            )
        except (
            self._client.exceptions.ConditionalCheckFailedException,
            # Another invocation's transaction was writing the video item at that moment.
            self._client.exceptions.TransactionConflictException,
        ):
            return False

        self._batch_write(writes)

        video_put = self._put(new_video_item)
        video_put["Put"]["ConditionExpression"] = f"{LOCK_ID} = :lockId"
        video_put["Put"]["ExpressionAttributeValues"] = {":lockId": lock_id}

        return self._put_holding_lock(
            new_video_item["videoId"], lock_id, [video_put, *extra_writes]
        )

    def _put_holding_lock(
        self, video_id: str, lock_id: str, writes: list[Write]
    ) -> bool:
        """Run the final transaction of a large apply, which drops our lock.

        While the video item still holds our lock, only this transaction is retried: retrying
        the whole apply would find the item locked -- by us -- and fail until the lock expired,
        leaving a placeholder's links visible meanwhile.
        """
        for attempt in range(self.MAX_ATTEMPTS):
            if attempt > 0:
                self._sleep(random.uniform(0, self.MAX_RETRY_JITTER_SECONDS))

            try:
                self._transact(writes)
                return True
            except self._client.exceptions.TransactionCanceledException as error:
                if not _lost_a_race(error):
                    raise

            current = self._table.get_item(
                Key=video_key(video_id), ConsistentRead=True
            ).get("Item")
            if (current or {}).get(LOCK_ID) != lock_id:
                # Our lock expired and was taken over; the new holder deletes any of our links
                # it doesn't want, since they are in its pendingLinkKeys. Re-read and retry.
                return False

        # Still holding the lock, but every attempt met a conflict. An SQS redelivery takes the
        # lock over once it expires.
        raise ConcurrentUpdateError(video_id)

    def _unchanged_and_unlocked(
        self, current: Mapping[str, Any] | None
    ) -> dict[str, Any]:
        """A condition that the video item is as it was read, and not locked by anyone."""
        now = epoch_seconds(self._clock())
        unlocked = f"(attribute_not_exists({LOCKED_UNTIL}) OR {LOCKED_UNTIL} < :now)"
        values: dict[str, Any] = {":now": now}

        if current is None:
            unchanged = "attribute_not_exists(PK)"
        else:
            if "capturedAt" not in current:
                unchanged = "attribute_exists(PK) AND attribute_not_exists(capturedAt)"
            else:
                # The raw stored value, even when unparseable: the put must still lose to any
                # write that landed since this read.
                unchanged = "capturedAt = :expected"
                values[":expected"] = current["capturedAt"]

            # A lock taken (and even expired) since the read may have written links that only
            # its pendingLinkKeys records, so the lock must also be the one that was read.
            if LOCK_ID in current:
                unchanged += f" AND {LOCK_ID} = :expectedLockId"
                values[":expectedLockId"] = current[LOCK_ID]
            else:
                unchanged += f" AND attribute_not_exists({LOCK_ID})"

        return {
            "ConditionExpression": f"{unchanged} AND {unlocked}",
            "ExpressionAttributeValues": values,
        }

    def _batch_write(self, writes: list[Write]) -> None:
        requests = [_batch_request(write) for write in writes]

        for start in range(0, len(requests), MAX_BATCH_WRITES):
            pending = requests[start : start + MAX_BATCH_WRITES]

            for attempt in range(MAX_BATCH_ATTEMPTS):
                if attempt > 0:
                    self._sleep(
                        random.uniform(0, self.MAX_RETRY_JITTER_SECONDS * 2**attempt)
                    )
                response = self._client.batch_write_item(
                    RequestItems={self._table_name: pending}
                )
                pending = response.get("UnprocessedItems", {}).get(self._table_name, [])
                if not pending:
                    break
            else:
                raise UnprocessedWritesError(
                    f"{len(pending)} link writes were still unprocessed after "
                    f"{MAX_BATCH_ATTEMPTS} attempts"
                )

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


def _batch_request(write: Write) -> dict[str, Any]:
    if "Put" in write:
        return {"PutRequest": {"Item": write["Put"]["Item"]}}

    return {"DeleteRequest": {"Key": write["Delete"]["Key"]}}


_RACE_CANCELLATION_CODES = frozenset({"ConditionalCheckFailed", "TransactionConflict"})


def _lost_a_race(error: Exception) -> bool:
    reasons = getattr(error, "response", {}).get("CancellationReasons", [])
    return any(reason.get("Code") in _RACE_CANCELLATION_CODES for reason in reasons)


def _key_tuple(key: dict[str, str]) -> tuple[str, str]:
    return key["PK"], key["SK"]
