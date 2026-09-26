import logging
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

import boto3
from boto3.dynamodb.conditions import Attr, Key
from botocore.exceptions import BotoCoreError, ClientError

from src.config.configuration import AppConfiguration
from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.scheduled_video import (
    PendingRequest,
    ScheduleListing,
    VideoSummary,
)
from src.services.models.user import Role, User
from src.sync.items import (
    ALL_VIDEOS_PARTITION,
    GSI1_NAME,
    PENDING_TTL,
    VIDEO_SORT_KEY,
    epoch_seconds,
    pending_key,
    user_partition,
)
from src.sync.messages import ScheduleRequest, to_json
from src.sync.page_tokens import decode_page_token, encode_page_token
from src.sync.timestamps import iso_micros

logger = logging.getLogger(__name__)

PAGE_SIZE = 25
# The longest URL POST /schedule accepts.
MAX_URL_LENGTH = 2048
# GET /schedule returns at most this many pending requests, newest first.
MAX_PENDING_REQUESTS = 100


class SchedulingService(ABC):
    @abstractmethod
    def schedule(self, url: str, user: User) -> str:
        """Queue a schedule request for the main API and return its request id."""

    @abstractmethod
    def list_schedules(
        self, user: User, status: str | None, page_token: str | None
    ) -> ScheduleListing:
        pass


def is_http_url(value: str) -> bool:
    try:
        # A lone surrogate can't be sent on as UTF-8 JSON, and urlparse raises on a malformed
        # IPv6 host such as "http://[::1"; reading .port also rejects a port out of range.
        value.encode("utf-8")
        parsed = urlparse(value)
        parsed.port
    except ValueError:
        return False

    return (
        parsed.scheme in ("http", "https")
        and bool(parsed.netloc)
        and not any(character.isspace() for character in value)
    )


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _new_request_id() -> str:
    return str(uuid4())


class DynamoDbSchedulingService(SchedulingService):
    def __init__(
        self,
        table,
        sqs_client,
        fallback_to_main_queue_url: str,
        clock: Callable[[], datetime] = _utc_now,
        request_id_generator: Callable[[], str] = _new_request_id,
    ):
        self._table = table
        self._sqs_client = sqs_client
        self._fallback_to_main_queue_url = fallback_to_main_queue_url
        self._clock = clock
        self._request_id_generator = request_id_generator

    def schedule(self, url: str, user: User) -> str:
        url = url.strip()
        if len(url) > MAX_URL_LENGTH:
            raise InvalidUrlException(f"URL is longer than {MAX_URL_LENGTH} characters")
        if not is_http_url(url):
            # The URL isn't quoted back: the 400 response is UTF-8 JSON, which a lone surrogate
            # in it would turn into a 500.
            raise InvalidUrlException("The URL is not an absolute http(s) URL")

        request = ScheduleRequest(
            request_id=self._request_id_generator(),
            user_id=user.id,
            url=url,
            requested_at=self._clock(),
        )

        # Queue first: if the pending write below fails, the request is still processed and its
        # result simply finds no pending item. The reverse order could strand a pending item.
        try:
            self._sqs_client.send_message(
                QueueUrl=self._fallback_to_main_queue_url, MessageBody=to_json(request)
            )
        except (BotoCoreError, ClientError) as error:
            raise ServiceUnavailableException(
                "Unable to queue the schedule request"
            ) from error

        try:
            self._put_pending(request, user, url)
        except (BotoCoreError, ClientError):
            # The request is already queued and will be processed, so this is still a success;
            # the user just won't see it as pending, nor see its reason if it is rejected.
            logger.exception(
                "Queued schedule request %s but failed to store its pending item",
                request.request_id,
            )

        return request.request_id

    def _put_pending(self, request: ScheduleRequest, user: User, url: str) -> None:
        self._table.put_item(
            Item={
                **pending_key(user.id, request.request_id),
                "requestId": request.request_id,
                "url": url,
                "requestedAt": iso_micros(request.requested_at),
                "status": "Pending",
                # Flow A sends to SQS before this write, so a fast RequestResolved could land
                # before this put and leave a "Pending" item forever. 14 days matches
                # FallbackToMainQueue's retention, so the request itself is gone by then.
                # Rejection (SyncApplier) overwrites this with a 7-day ttl.
                "ttl": epoch_seconds(request.requested_at + PENDING_TTL),
            }
        )

    def list_schedules(
        self, user: User, status: str | None, page_token: str | None
    ) -> ScheduleListing:
        query: dict[str, Any] = {"Limit": PAGE_SIZE, "ScanIndexForward": False}

        if user.role == Role.ADMIN:
            query["IndexName"] = GSI1_NAME
            query["KeyConditionExpression"] = Key("GSI1PK").eq(ALL_VIDEOS_PARTITION)
            # A GSI query's LastEvaluatedKey carries both the index key and the table's own
            # primary key, so a real page token here has all four attributes.
            expected_keys = frozenset({"PK", "SK", "GSI1PK", "GSI1SK"})
            equals = {"GSI1PK": ALL_VIDEOS_PARTITION, "SK": VIDEO_SORT_KEY}
            prefixes = {"PK": "VIDEO#"}
        else:
            partition = user_partition(user.id)
            query["KeyConditionExpression"] = Key("PK").eq(partition) & Key(
                "SK"
            ).begins_with("VIDEO#")
            expected_keys = frozenset({"PK", "SK"})
            equals = {"PK": partition}
            prefixes = {"SK": "VIDEO#"}

        if status is not None:
            query["FilterExpression"] = Attr("status").eq(status)

        if page_token is not None:
            query["ExclusiveStartKey"] = self._start_key(
                page_token, expected_keys, equals, prefixes
            )

        response = self._table.query(**query)
        last_evaluated_key = response.get("LastEvaluatedKey")

        return ScheduleListing(
            videos=[VideoSummary.from_item(item) for item in response["Items"]],
            pending=self._pending_requests(user.id),
            next_page_token=(
                None
                if last_evaluated_key is None
                else encode_page_token(last_evaluated_key)
            ),
        )

    @staticmethod
    def _start_key(
        page_token: str,
        expected_keys: frozenset[str],
        equals: Mapping[str, str],
        prefixes: Mapping[str, str],
    ) -> dict[str, str]:
        """Reject tokens that don't match this listing's exact DynamoDB key schema.

        A wrong-shaped ExclusiveStartKey (a missing or extra attribute, or a key value DynamoDB
        itself would refuse -- empty, or longer than 1024 bytes) makes DynamoDB itself raise
        ValidationException -- a ClientError that would surface as a 500 -- so both the key *set*
        and each value are checked here, not just the values of the keys we care about. This also
        catches tokens belonging to another listing, e.g. another user's partition or the admin
        index.
        """
        start_key = decode_page_token(page_token)

        if frozenset(start_key) != expected_keys:
            raise InvalidPageTokenException(
                "Page token does not match the listing's key schema"
            )

        if any(not 0 < len(value.encode()) <= 1024 for value in start_key.values()):
            raise InvalidPageTokenException(
                "Page token contains a key value DynamoDB would reject"
            )

        if any(start_key[name] != value for name, value in equals.items()):
            raise InvalidPageTokenException(
                "Page token does not belong to this listing"
            )

        if any(
            not start_key[name].startswith(prefix) for name, prefix in prefixes.items()
        ):
            raise InvalidPageTokenException(
                "Page token does not belong to this listing"
            )

        return start_key

    def _pending_requests(self, user_id: str) -> list[PendingRequest]:
        """The user's newest live pending requests, by requestedAt.

        Every pending item is read and then sorted, rather than querying newest-first with a
        Limit: the sort key is PENDING#<requestId>, and request ids are random UUIDs, so the sort
        key order says nothing about age. Keying by time instead would need requestedAt in
        RequestResolved, which only carries the request id. The read is bounded by the requests
        the user made within PENDING_TTL, less those already resolved and removed.
        """
        query: dict[str, Any] = {
            "KeyConditionExpression": Key("PK").eq(user_partition(user_id))
            & Key("SK").begins_with("PENDING#"),
            # DynamoDB deletes expired items lazily, up to days after their ttl.
            "FilterExpression": Attr("ttl").not_exists()
            | Attr("ttl").gt(epoch_seconds(self._clock())),
        }
        items: list[Mapping[str, Any]] = []

        while True:
            response = self._table.query(**query)
            items.extend(response["Items"])
            if "LastEvaluatedKey" not in response:
                break
            query["ExclusiveStartKey"] = response["LastEvaluatedKey"]

        pending = [PendingRequest.from_item(item) for item in items]
        newest_first = sorted(
            pending, key=lambda request: request.requested_at, reverse=True
        )
        return newest_first[:MAX_PENDING_REQUESTS]


def get_scheduling_service(app_configuration: AppConfiguration) -> SchedulingService:
    sync_configuration = app_configuration.sync

    return DynamoDbSchedulingService(
        boto3.resource("dynamodb").Table(sync_configuration.table_name),
        boto3.client("sqs"),
        sync_configuration.fallback_to_main_queue_url,
    )
