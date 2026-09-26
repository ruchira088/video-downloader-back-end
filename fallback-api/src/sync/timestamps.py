from datetime import UTC, datetime


def iso_millis(value: datetime) -> str:
    """The single timestamp format used in sync messages and DynamoDB items.

    Fixed-width UTC strings sort lexicographically in time order, which the capturedAt
    guard and the GSI sort keys rely on.
    """
    if value.tzinfo is None:
        raise ValueError("Timestamps must be timezone-aware")

    return (
        value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    )
