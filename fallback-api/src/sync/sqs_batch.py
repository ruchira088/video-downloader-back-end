import logging
from collections.abc import Mapping
from typing import Any

from src.sync.messages import UserRevocation, parse_main_to_fallback_message
from src.sync.sync_applier import SyncApplier
from src.sync.user_revoker import CognitoUserRevoker

logger = logging.getLogger(__name__)


def process_sqs_batch(
    event: Mapping[str, Any],
    applier: SyncApplier,
    user_revoker: CognitoUserRevoker | None = None,
) -> dict[str, list[dict[str, str]]]:
    """Apply each record on its own; failed records are retried by SQS, then dead-lettered."""
    failures: list[dict[str, str]] = []

    for record in event.get("Records", []):
        message_id: str = record["messageId"]
        try:
            message = parse_main_to_fallback_message(record["body"])
            if isinstance(message, UserRevocation):
                if user_revoker is None:
                    raise RuntimeError("No user revoker is configured")
                user_revoker.revoke(message)
            else:
                applier.apply(message)
        except Exception:
            logger.exception("Failed to apply sync message %s", message_id)
            failures.append({"itemIdentifier": message_id})

    return {"batchItemFailures": failures}
