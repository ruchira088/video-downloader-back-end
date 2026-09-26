from typing import Annotated, Any, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    PlainSerializer,
    TypeAdapter,
)
from pydantic.alias_generators import to_camel

from src.sync.timestamps import iso_micros, require_iso_micros


def _require_fixed_width(value: Any) -> Any:
    # Strings (from JSON) must be in the exact shared format; datetimes built in code are
    # accepted as they are and formatted on the way out.
    return require_iso_micros(value) if isinstance(value, str) else value


Timestamp = Annotated[
    AwareDatetime,
    BeforeValidator(_require_fixed_width),
    PlainSerializer(iso_micros, return_type=str, when_used="json"),
]


class SyncMessage(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, frozen=True
    )


class ScheduledVideoUpsert(SyncMessage):
    type: Literal["ScheduledVideoUpsert"] = "ScheduledVideoUpsert"
    video_id: str
    captured_at: Timestamp
    hash: str
    user_ids: list[str]
    url: str
    video_site: str
    title: str
    duration_ms: int
    size_bytes: int
    status: str
    scheduled_at: Timestamp
    completed_at: Timestamp | None = None


class ScheduledVideoRemoval(SyncMessage):
    type: Literal["ScheduledVideoRemoval"] = "ScheduledVideoRemoval"
    video_id: str
    captured_at: Timestamp


class ScheduledOutcome(SyncMessage):
    result: Literal["Scheduled"] = "Scheduled"
    upsert: ScheduledVideoUpsert


class RejectedOutcome(SyncMessage):
    result: Literal["Rejected"] = "Rejected"
    reason: str


class RequestResolved(SyncMessage):
    type: Literal["RequestResolved"] = "RequestResolved"
    request_id: str
    user_id: str
    outcome: Annotated[
        ScheduledOutcome | RejectedOutcome, Field(discriminator="result")
    ]


class ScheduleRequest(SyncMessage):
    type: Literal["ScheduleRequest"] = "ScheduleRequest"
    request_id: str
    user_id: str
    url: str
    requested_at: Timestamp


MainToFallbackMessage = Annotated[
    ScheduledVideoUpsert | ScheduledVideoRemoval | RequestResolved,
    Field(discriminator="type"),
]

_main_to_fallback_adapter: TypeAdapter[MainToFallbackMessage] = TypeAdapter(
    MainToFallbackMessage
)


def parse_main_to_fallback_message(body: str | bytes) -> MainToFallbackMessage:
    return _main_to_fallback_adapter.validate_json(body)


def to_json(message: SyncMessage) -> str:
    return message.model_dump_json(by_alias=True, exclude_none=True)
