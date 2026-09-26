from collections.abc import Mapping
from datetime import datetime, timedelta
from typing import Any

from src.sync.messages import ScheduledVideoUpsert
from src.sync.timestamps import iso_millis

VIDEO_SORT_KEY = "VIDEO"
ALL_VIDEOS_PARTITION = "VIDEO"
GSI1_NAME = "GSI1"
DELETED_STATUS = "Deleted"
TOMBSTONE_TTL = timedelta(days=1)
REJECTED_TTL = timedelta(days=7)


def video_key(video_id: str) -> dict[str, str]:
    return {"PK": f"VIDEO#{video_id}", "SK": VIDEO_SORT_KEY}


def user_partition(user_id: str) -> str:
    return f"USER#{user_id}"


def link_key(user_id: str, scheduled_at: str, video_id: str) -> dict[str, str]:
    return {"PK": user_partition(user_id), "SK": f"VIDEO#{scheduled_at}#{video_id}"}


def pending_key(user_id: str, request_id: str) -> dict[str, str]:
    return {"PK": user_partition(user_id), "SK": f"PENDING#{request_id}"}


def epoch_seconds(value: datetime) -> int:
    return int(value.timestamp())


def display_fields(upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "videoId": upsert.video_id,
        "url": upsert.url,
        "videoSite": upsert.video_site,
        "title": upsert.title,
        "durationMs": upsert.duration_ms,
        "sizeBytes": upsert.size_bytes,
        "status": upsert.status,
        "scheduledAt": iso_millis(upsert.scheduled_at),
    }

    if upsert.completed_at is not None:
        fields["completedAt"] = iso_millis(upsert.completed_at)

    return fields


def link_item(user_id: str, upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    scheduled_at = iso_millis(upsert.scheduled_at)

    return {
        **link_key(user_id, scheduled_at, upsert.video_id),
        **display_fields(upsert),
    }


def video_item(upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    scheduled_at = iso_millis(upsert.scheduled_at)

    return {
        **video_key(upsert.video_id),
        **display_fields(upsert),
        "userIds": sorted(set(upsert.user_ids)),
        "capturedAt": iso_millis(upsert.captured_at),
        "hash": upsert.hash,
        "deleted": False,
        "GSI1PK": ALL_VIDEOS_PARTITION,
        "GSI1SK": f"{scheduled_at}#{upsert.video_id}",
    }


def tombstone_item(
    video_id: str, captured_at: datetime, now: datetime
) -> dict[str, Any]:
    return {
        **video_key(video_id),
        "videoId": video_id,
        "capturedAt": iso_millis(captured_at),
        "deleted": True,
        "ttl": epoch_seconds(now + TOMBSTONE_TTL),
    }


def live_link_keys(
    current: Mapping[str, Any] | None,
    video_id: str,
    keep: frozenset[str] = frozenset(),
) -> list[dict[str, str]]:
    """Keys of the user links a stored video item currently has, except those in `keep`."""
    if current is None or current.get("deleted"):
        return []

    return [
        link_key(user_id, current["scheduledAt"], video_id)
        for user_id in sorted(current["userIds"])
        if user_id not in keep
    ]
