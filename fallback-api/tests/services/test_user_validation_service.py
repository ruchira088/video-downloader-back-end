import unittest
from unittest.mock import MagicMock, patch

from src.services.models.user import Role
from src.services.user_validation_service import VideoDownloaderUserValidationService


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
