import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient

from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.scheduled_video import (
    PendingRequest,
    ScheduleListing,
    VideoSummary,
)
from src.services.models.user import User
from src.services.scheduling_service import SchedulingService
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.schedule_router import schedule_router

USER = User(id="user-1", email="u1@ruchij.com", first_name="U", last_name="One")


class FakeSchedulingService(SchedulingService):
    def __init__(self):
        self.error: Exception | None = None
        self.calls: list[tuple] = []

    def schedule(self, url: str, user: User) -> str:
        self.calls.append(("schedule", url, user.id))
        if self.error is not None:
            raise self.error
        return "request-1"

    def list_schedules(self, user, status, page_token) -> ScheduleListing:
        self.calls.append(("list", user.id, status, page_token))
        if self.error is not None:
            raise self.error
        return ScheduleListing(
            videos=[
                VideoSummary(
                    video_id="v1",
                    url="https://www.youtube.com/watch?v=abc",
                    video_site="YouTube",
                    title="Sample",
                    duration_ms=1000,
                    size_bytes=2000,
                    status="Queued",
                    scheduled_at="2026-09-26T07:00:00.000000Z",
                )
            ],
            pending=[
                PendingRequest(
                    request_id="request-1",
                    url="https://www.youtube.com/watch?v=def",
                    requested_at="2026-09-26T08:00:00.000000Z",
                    status="Pending",
                )
            ],
            next_page_token="token-2",
        )


class TestScheduleRouter(unittest.TestCase):
    def setUp(self):
        self.service = FakeSchedulingService()
        app = FastAPI()
        app.include_router(schedule_router(self.service, lambda: USER))
        register_exception_handlers(app)
        self.client = TestClient(app)

    def test_post_schedule_returns_202_with_the_request_id(self):
        response = self.client.post(
            "/schedule", json={"url": "https://www.youtube.com/watch?v=abc"}
        )

        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.json(), {"requestId": "request-1"})
        self.assertEqual(
            self.service.calls,
            [("schedule", "https://www.youtube.com/watch?v=abc", "user-1")],
        )

    def test_invalid_url_returns_400(self):
        self.service.error = InvalidUrlException(
            "The URL is not an absolute http(s) URL"
        )

        response = self.client.post("/schedule", json={"url": "x"})

        self.assertEqual(response.status_code, 400)

    def test_queue_failure_returns_503(self):
        self.service.error = ServiceUnavailableException(
            "Unable to queue the schedule request"
        )

        response = self.client.post("/schedule", json={"url": "https://example.com/v"})

        self.assertEqual(response.status_code, 503)

    def test_get_schedule_returns_camel_case_listing(self):
        response = self.client.get(
            "/schedule", params={"status": "Queued", "pageToken": "t1"}
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["videos"][0]["videoId"], "v1")
        self.assertEqual(body["videos"][0]["durationMs"], 1000)
        self.assertEqual(body["pending"][0]["requestId"], "request-1")
        self.assertEqual(body["nextPageToken"], "token-2")
        self.assertEqual(self.service.calls, [("list", "user-1", "Queued", "t1")])

    def test_invalid_page_token_returns_400(self):
        self.service.error = InvalidPageTokenException("Malformed page token")

        response = self.client.get("/schedule", params={"pageToken": "garbage"})

        self.assertEqual(response.status_code, 400)
