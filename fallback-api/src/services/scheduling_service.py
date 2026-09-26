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
    epoch_seconds,
    pending_key,
    user_partition,
)
from src.sync.messages import ScheduleRequest, to_json
from src.sync.page_tokens import decode_page_token, encode_page_token
from src.sync.timestamps import iso_millis

PAGE_SIZE = 25


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
    parsed = urlparse(value)

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
        if not is_http_url(url):
            raise InvalidUrlException(f'"{url}" is not an absolute http(s) URL')

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

        self._table.put_item(
            Item={
                **pending_key(user.id, request.request_id),
                "requestId": request.request_id,
                "url": url,
                "requestedAt": iso_millis(request.requested_at),
                "status": "Pending",
                # Flow A sends to SQS before this write, so a fast RequestResolved could land
                # before this put and leave a "Pending" item forever. 14 days matches SQS
                # retention. Rejection (SyncApplier) overwrites this with a 7-day ttl.
                "ttl": epoch_seconds(request.requested_at + PENDING_TTL),
            }
        )

        return request.request_id

    def list_schedules(
        self, user: User, status: str | None, page_token: str | None
    ) -> ScheduleListing:
        query: dict[str, Any] = {"Limit": PAGE_SIZE, "ScanIndexForward": False}

        if user.role == Role.ADMIN:
            query["IndexName"] = GSI1_NAME
            query["KeyConditionExpression"] = Key("GSI1PK").eq(ALL_VIDEOS_PARTITION)
            required: Mapping[str, str] = {"GSI1PK": ALL_VIDEOS_PARTITION}
            sort_key_prefix: str | None = None
        else:
            partition = user_partition(user.id)
            query["KeyConditionExpression"] = Key("PK").eq(partition) & Key(
                "SK"
            ).begins_with("VIDEO#")
            required = {"PK": partition}
            sort_key_prefix = "VIDEO#"

        if status is not None:
            query["FilterExpression"] = Attr("status").eq(status)

        if page_token is not None:
            query["ExclusiveStartKey"] = self._start_key(
                page_token, required, sort_key_prefix
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
        page_token: str, required: Mapping[str, str], sort_key_prefix: str | None
    ) -> dict[str, str]:
        """Reject tokens that point outside the caller's own query, e.g. another user's partition."""
        start_key = decode_page_token(page_token)

        if any(start_key.get(name) != value for name, value in required.items()):
            raise InvalidPageTokenException(
                "Page token does not belong to this listing"
            )

        if sort_key_prefix is not None and not start_key.get("SK", "").startswith(
            sort_key_prefix
        ):
            raise InvalidPageTokenException(
                "Page token does not belong to this listing"
            )

        return start_key

    def _pending_requests(self, user_id: str) -> list[PendingRequest]:
        query: dict[str, Any] = {
            "KeyConditionExpression": Key("PK").eq(user_partition(user_id))
            & Key("SK").begins_with("PENDING#")
        }
        items: list[Mapping[str, Any]] = []

        while True:
            response = self._table.query(**query)
            items.extend(response["Items"])
            if "LastEvaluatedKey" not in response:
                break
            query["ExclusiveStartKey"] = response["LastEvaluatedKey"]

        pending = [PendingRequest.from_item(item) for item in items]
        return sorted(pending, key=lambda request: request.requested_at, reverse=True)


def get_scheduling_service(app_configuration: AppConfiguration) -> SchedulingService:
    sync_configuration = app_configuration.sync

    return DynamoDbSchedulingService(
        boto3.resource("dynamodb").Table(sync_configuration.table_name),
        boto3.client("sqs"),
        sync_configuration.fallback_to_main_queue_url,
    )
