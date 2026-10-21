import unittest
from unittest.mock import MagicMock

from src.services.deadline import (
    API_GATEWAY_TIMEOUT_SECONDS,
    SAFETY_MARGIN_SECONDS,
    Deadline,
)


class FakeClock:
    def __init__(self, now: float = 1000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def lambda_context(remaining_millis: int) -> MagicMock:
    context = MagicMock()
    context.get_remaining_time_in_millis.return_value = remaining_millis
    return context


class TestDeadline(unittest.TestCase):
    def test_remaining_counts_down_and_stops_at_zero(self):
        clock = FakeClock()
        deadline = Deadline(10, clock=clock)

        self.assertEqual(deadline.remaining(), 10)
        clock.now += 4
        self.assertEqual(deadline.remaining(), 6)
        clock.now += 20
        self.assertEqual(deadline.remaining(), 0)

    def test_a_request_gets_the_lambdas_remaining_time_less_a_safety_margin(self):
        deadline = Deadline.for_request(lambda_context(12_000), clock=FakeClock())

        self.assertEqual(deadline.remaining(), 12 - SAFETY_MARGIN_SECONDS)

    def test_a_request_never_gets_more_than_api_gateways_timeout(self):
        # The Lambda's 30 s outlasts API Gateway's 29 s, which then answers the client
        deadline = Deadline.for_request(lambda_context(30_000), clock=FakeClock())

        self.assertEqual(
            deadline.remaining(), API_GATEWAY_TIMEOUT_SECONDS - SAFETY_MARGIN_SECONDS
        )

    def test_a_request_outside_lambda_gets_api_gateways_timeout(self):
        deadline = Deadline.for_request(None, clock=FakeClock())

        self.assertEqual(
            deadline.remaining(), API_GATEWAY_TIMEOUT_SECONDS - SAFETY_MARGIN_SECONDS
        )

    def test_a_request_with_less_time_than_the_margin_has_none(self):
        deadline = Deadline.for_request(lambda_context(500), clock=FakeClock())

        self.assertEqual(deadline.remaining(), 0)
