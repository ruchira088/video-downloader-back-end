from datetime import UTC, datetime, timedelta
from typing import Any

from src.sync.messages import ScheduledVideoUpsert

FIXED_NOW = datetime(2026, 9, 26, 8, 0, tzinfo=UTC)
T0 = datetime(2026, 9, 26, 7, 0, tzinfo=UTC)


def sample_upsert(**overrides: Any) -> ScheduledVideoUpsert:
    values: dict[str, Any] = {
        "video_id": "youtube-abc",
        "captured_at": T0,
        "hash": "0123456789abcdef",
        "user_ids": ["user-1", "user-2"],
        "url": "https://www.youtube.com/watch?v=abc",
        "video_site": "YouTube",
        "title": "Sample video",
        "duration_ms": 212000,
        "size_bytes": 48234567,
        "status": "Queued",
        "scheduled_at": datetime(2026, 9, 25, 21, 4, 11, tzinfo=UTC),
    }
    values.update(overrides)

    return ScheduledVideoUpsert(**values)


def later(minutes: int) -> datetime:
    return T0 + timedelta(minutes=minutes)
