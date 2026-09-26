import unittest
from unittest.mock import MagicMock, patch

import requests as real_requests

from src.services.exceptions import ServiceUnavailableException
from src.services.models.user import Role
from src.services.user_validation_service import (
    MAIN_API_TIMEOUT_SECONDS,
    VideoDownloaderUserValidationService,
)


def _logout_response(body: dict) -> MagicMock:
    response = MagicMock()
    response.json.return_value = body
    return response


class TestVideoDownloaderUserValidationService(unittest.TestCase):
    def _get_user(self, logout_body: dict):
        login_response = MagicMock()
        login_response.json.return_value = {"secret": "token"}

        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = login_response
            requests.delete.return_value = _logout_response(logout_body)
            service = VideoDownloaderUserValidationService(
                "https://api.example.com"  # type: ignore[arg-type]
            )
            return service.get_user(email="me@ruchij.com", password="secret")

    def _body(self, **overrides) -> dict:
        body = {
            "id": "user-1",
            "email": "me@ruchij.com",
            "firstName": "John",
            "lastName": "Doe",
        }
        body.update(overrides)
        return body

    def test_admin_role_is_read_from_the_logout_response(self):
        user = self._get_user(self._body(role="Admin"))

        self.assertEqual(user.role, Role.ADMIN)

    def test_missing_role_defaults_to_user(self):
        user = self._get_user(self._body())

        self.assertEqual(user.role, Role.USER)

    def test_unknown_role_defaults_to_user(self):
        user = self._get_user(self._body(role="SuperAdmin"))

        self.assertEqual(user.role, Role.USER)

    def test_calls_to_the_main_api_have_a_timeout(self):
        login_response = MagicMock()
        login_response.json.return_value = {"secret": "token"}

        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = login_response
            requests.delete.return_value = _logout_response(self._body())
            VideoDownloaderUserValidationService(
                "https://api.example.com"  # type: ignore[arg-type]
            ).get_user(email="me@ruchij.com", password="secret")

        self.assertEqual(MAIN_API_TIMEOUT_SECONDS, 10)
        self.assertEqual(requests.post.call_args.kwargs["timeout"], 10)
        self.assertEqual(requests.delete.call_args.kwargs["timeout"], 10)

    def test_an_unreachable_main_api_is_reported_as_unavailable(self):
        for error in [
            real_requests.Timeout("timed out"),
            real_requests.ConnectionError("refused"),
        ]:
            with (
                self.subTest(error=type(error).__name__),
                patch("src.services.user_validation_service.requests") as requests,
            ):
                requests.post.side_effect = error
                service = VideoDownloaderUserValidationService(
                    "https://api.example.com"  # type: ignore[arg-type]
                )

                with self.assertRaises(ServiceUnavailableException):
                    service.get_user(email="me@ruchij.com", password="secret")
