import logging
from collections.abc import Mapping
from typing import Any

from src.sync.messages import parse_main_to_fallback_message
from src.sync.sync_applier import SyncApplier

logger = logging.getLogger(__name__)


def process_sqs_batch(
    event: Mapping[str, Any], applier: SyncApplier
) -> dict[str, list[dict[str, str]]]:
    """Apply each record on its own; failed records are retried by SQS, then dead-lettered."""
    failures: list[dict[str, str]] = []

    for record in event.get("Records", []):
        message_id: str = record["messageId"]
        try:
            applier.apply(parse_main_to_fallback_message(record["body"]))
        except Exception:
            logger.exception("Failed to apply sync message %s", message_id)
            failures.append({"itemIdentifier": message_id})

    return {"batchItemFailures": failures}
