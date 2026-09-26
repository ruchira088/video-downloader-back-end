import re
from datetime import UTC, datetime

# yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z' -- shared with the main API, which writes the same format.
_TIMESTAMP_FORMAT = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z"
)


def iso_micros(value: datetime) -> str:
    """The single timestamp format used in sync messages and DynamoDB items.

    Always UTC with exactly six fractional digits and a trailing "Z". Fixed-width UTC strings
    sort lexicographically in time order, which the GSI and user-link sort keys rely on.
    """
    if value.tzinfo is None:
        raise ValueError("Timestamps must be timezone-aware")

    return (
        value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")
    )


def require_iso_micros(value: str) -> str:
    """Return the value unchanged if it is in the fixed-width format, else raise ValueError."""
    if not _TIMESTAMP_FORMAT.fullmatch(value):
        raise ValueError(
            f'"{value}" is not a UTC timestamp of the form 2026-09-26T08:15:30.123456Z'
        )

    return value


def parse_iso_micros(value: str) -> datetime:
    return datetime.fromisoformat(require_iso_micros(value))
