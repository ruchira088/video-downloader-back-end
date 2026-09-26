from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class VideoSummary(CamelModel):
    video_id: str
    url: str
    video_site: str
    title: str
    duration_ms: int
    size_bytes: int
    status: str
    scheduled_at: str
    completed_at: str | None = None

    @classmethod
    def from_item(cls, item: Mapping[str, Any]) -> "VideoSummary":
        return cls(
            video_id=item["videoId"],
            url=item["url"],
            video_site=item["videoSite"],
            title=item["title"],
            duration_ms=int(item["durationMs"]),
            size_bytes=int(item["sizeBytes"]),
            status=item["status"],
            scheduled_at=item["scheduledAt"],
            completed_at=item.get("completedAt"),
        )


class PendingRequest(CamelModel):
    request_id: str
    url: str
    requested_at: str
    status: str
    reason: str | None = None

    @classmethod
    def from_item(cls, item: Mapping[str, Any]) -> "PendingRequest":
        return cls(
            request_id=item["requestId"],
            url=item["url"],
            requested_at=item["requestedAt"],
            status=item["status"],
            reason=item.get("reason"),
        )


class ScheduleListing(CamelModel):
    videos: list[VideoSummary]
    pending: list[PendingRequest]
    next_page_token: str | None = None
