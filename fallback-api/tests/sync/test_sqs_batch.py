import unittest
from unittest.mock import MagicMock

from src.sync.messages import ScheduledVideoRemoval, UserRevocation, to_json
from src.sync.sqs_batch import process_sqs_batch
from tests.sync.sync_test_data import T0, sample_upsert


def _record(message_id: str, body: str) -> dict:
    return {"messageId": message_id, "body": body}


class TestProcessSqsBatch(unittest.TestCase):
    def test_all_records_applied_reports_no_failures(self):
        applier = MagicMock()
        event = {
            "Records": [
                _record("m1", to_json(sample_upsert())),
                _record(
                    "m2", to_json(ScheduledVideoRemoval(video_id="v", captured_at=T0))
                ),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(response, {"batchItemFailures": []})
        self.assertEqual(applier.apply.call_count, 2)

    def test_malformed_and_unknown_messages_fail_only_their_own_record(self):
        applier = MagicMock()
        event = {
            "Records": [
                _record("bad-json", "{not json"),
                _record("unknown-type", '{"type": "SomethingElse"}'),
                _record("good", to_json(sample_upsert())),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(
            response,
            {
                "batchItemFailures": [
                    {"itemIdentifier": "bad-json"},
                    {"itemIdentifier": "unknown-type"},
                ]
            },
        )
        applier.apply.assert_called_once()

    def test_an_apply_error_fails_only_that_record(self):
        applier = MagicMock()
        applier.apply.side_effect = [RuntimeError("boom"), None]
        event = {
            "Records": [
                _record("m1", to_json(sample_upsert())),
                _record("m2", to_json(sample_upsert(video_id="other"))),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(response, {"batchItemFailures": [{"itemIdentifier": "m1"}]})

    def test_a_user_revocation_goes_to_the_user_revoker_not_the_applier(self):
        applier = MagicMock()
        user_revoker = MagicMock()
        revocation = UserRevocation(
            user_id="user-1", email="me@ruchij.com", captured_at=T0
        )
        event = {
            "Records": [
                _record("m1", to_json(revocation)),
                _record("m2", to_json(sample_upsert())),
            ]
        }

        response = process_sqs_batch(event, applier, user_revoker)

        self.assertEqual(response, {"batchItemFailures": []})
        user_revoker.revoke.assert_called_once_with(revocation)
        applier.apply.assert_called_once_with(sample_upsert())

    def test_a_failed_revocation_fails_only_its_own_record(self):
        applier = MagicMock()
        user_revoker = MagicMock()
        user_revoker.revoke.side_effect = RuntimeError("throttled")
        revocation = UserRevocation(
            user_id="user-1", email="me@ruchij.com", captured_at=T0
        )
        event = {
            "Records": [
                _record("m1", to_json(revocation)),
                _record("m2", to_json(sample_upsert())),
            ]
        }

        response = process_sqs_batch(event, applier, user_revoker)

        self.assertEqual(response, {"batchItemFailures": [{"itemIdentifier": "m1"}]})
        applier.apply.assert_called_once()

    def test_a_revocation_without_a_user_revoker_fails_its_record(self):
        # So it is retried and dead-lettered, raising the alarm, rather than lost.
        revocation = UserRevocation(
            user_id="user-1", email="me@ruchij.com", captured_at=T0
        )
        event = {"Records": [_record("m1", to_json(revocation))]}

        response = process_sqs_batch(event, MagicMock())

        self.assertEqual(response, {"batchItemFailures": [{"itemIdentifier": "m1"}]})
