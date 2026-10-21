import time
from collections.abc import Callable
from typing import Any

# API Gateway answers the client with a 504 after 29 s, before the Lambda's own 30 s timeout
API_GATEWAY_TIMEOUT_SECONDS = 29.0
# Kept back from the request's time for the work after the last deadline-bound call (the
# response, and Cognito calls that aren't bounded by it), and for the time API Gateway spent
# before the Lambda started
SAFETY_MARGIN_SECONDS = 2.0


class Deadline:
    """The time a request has left, shared by every call it makes, so that a slow call leaves
    the later ones less rather than running the request past API Gateway's or the Lambda's
    timeout."""

    def __init__(self, seconds: float, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._ends_at = clock() + seconds

    @classmethod
    def for_request(
        cls, lambda_context: Any | None, clock: Callable[[], float] = time.monotonic
    ) -> "Deadline":
        """lambda_context is the Lambda context Mangum puts in the ASGI scope as aws.context,
        or None outside Lambda (e.g. under uvicorn locally)."""
        seconds = API_GATEWAY_TIMEOUT_SECONDS
        if lambda_context is not None:
            seconds = min(seconds, lambda_context.get_remaining_time_in_millis() / 1000)

        return cls(max(seconds - SAFETY_MARGIN_SECONDS, 0.0), clock=clock)

    def remaining(self) -> float:
        return max(self._ends_at - self._clock(), 0.0)
