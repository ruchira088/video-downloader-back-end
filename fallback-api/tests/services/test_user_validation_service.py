import unittest
from collections.abc import Callable
from unittest.mock import MagicMock, patch

import requests as real_requests

from src.services.deadline import Deadline
from src.services.exceptions import ServiceUnavailableException
from src.services.models.user import Role
from src.services.user_validation_service import (
    MAIN_API_TIMEOUT_SECONDS,
    VideoDownloaderUserValidationService,
)
from tests.services.test_deadline import FakeClock


def _response(body: dict) -> MagicMock:
    response = MagicMock()
    response.json.return_value = body
    return response


def _login_response() -> MagicMock:
    return _response({"userId": "user-1", "secret": "token"})


def _ample_deadline() -> Deadline:
    return Deadline(25, clock=FakeClock())


class TestVideoDownloaderUserValidationService(unittest.TestCase):
    def setUp(self):
        self.background_tasks: list[Callable[[], None]] = []
        self.service = VideoDownloaderUserValidationService(
            "https://api.example.com",  # type: ignore[arg-type]
            run_in_background=self.background_tasks.append,
        )

    def _get_user(self, logout_body: dict):
        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = _login_response()
            requests.delete.return_value = _response(logout_body)
            return self.service.get_user(
                email="me@ruchij.com", password="secret", deadline=_ample_deadline()
            )

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
        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = _login_response()
            requests.delete.return_value = _response(self._body())
            self.service.get_user(
                email="me@ruchij.com", password="secret", deadline=_ample_deadline()
            )

        self.assertEqual(MAIN_API_TIMEOUT_SECONDS, (3, 10))
        self.assertEqual(requests.post.call_args.kwargs["timeout"], (3, 10))
        self.assertEqual(requests.delete.call_args.kwargs["timeout"], (3, 10))

    def test_each_call_times_out_within_the_requests_remaining_time(self):
        clock = FakeClock()
        deadline = Deadline(12, clock=clock)

        def slow_login(*args, **kwargs):
            clock.now += 10
            return _login_response()

        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.side_effect = slow_login
            requests.delete.return_value = _response(self._body())
            self.service.get_user(
                email="me@ruchij.com", password="secret", deadline=deadline
            )

        self.assertEqual(requests.post.call_args.kwargs["timeout"], (3, 10))
        self.assertEqual(requests.delete.call_args.kwargs["timeout"], (2, 2))

    def test_no_call_is_made_once_the_request_has_run_out_of_time(self):
        clock = FakeClock()
        deadline = Deadline(5, clock=clock)
        clock.now += 5

        with patch("src.services.user_validation_service.requests") as requests:
            with self.assertRaises(ServiceUnavailableException):
                self.service.get_user(
                    email="me@ruchij.com", password="secret", deadline=deadline
                )
            with self.assertRaises(ServiceUnavailableException):
                self.service.check_credentials(
                    email="me@ruchij.com", password="secret", deadline=deadline
                )

        requests.post.assert_not_called()
        requests.delete.assert_not_called()

    def test_checking_credentials_returns_the_user_id_without_waiting_for_the_logout(
        self,
    ):
        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = _login_response()
            requests.delete.return_value = _response(self._body())

            user_id = self.service.check_credentials(
                email="me@ruchij.com", password="secret", deadline=_ample_deadline()
            )

            self.assertEqual(user_id, "user-1")
            # The logout only ends the session the check opened, so it is left to run on its
            # own rather than spend the request's time
            requests.delete.assert_not_called()
            self.assertEqual(len(self.background_tasks), 1)

            self.background_tasks[0]()

        self.assertEqual(
            requests.delete.call_args.kwargs["headers"],
            {"Authorization": "Bearer token"},
        )

    def test_a_failed_background_logout_is_ignored(self):
        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = _login_response()
            requests.delete.side_effect = real_requests.Timeout("timed out")

            self.service.check_credentials(
                email="me@ruchij.com", password="secret", deadline=_ample_deadline()
            )
            self.background_tasks[0]()

    def test_checking_credentials_falls_back_to_the_logout_without_a_user_id(self):
        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = _response({"secret": "token"})
            requests.delete.return_value = _response(self._body())

            user_id = self.service.check_credentials(
                email="me@ruchij.com", password="secret", deadline=_ample_deadline()
            )

        self.assertEqual(user_id, "user-1")
        requests.delete.assert_called_once()
        self.assertEqual(self.background_tasks, [])

    def test_a_refused_credential_check_raises_the_main_apis_error(self):
        refusal = real_requests.HTTPError("401 error")
        login_response = MagicMock()
        login_response.raise_for_status.side_effect = refusal

        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = login_response

            with self.assertRaises(real_requests.HTTPError) as raised:
                self.service.check_credentials(
                    email="me@ruchij.com",
                    password="secret",
                    deadline=_ample_deadline(),
                )

        self.assertIs(raised.exception, refusal)
        self.assertEqual(self.background_tasks, [])

    def test_an_unreachable_main_api_is_reported_as_unavailable(self):
        for error in [
            real_requests.Timeout("timed out"),
            real_requests.ConnectionError("refused"),
        ]:
            for check in [self.service.get_user, self.service.check_credentials]:
                with (
                    self.subTest(error=type(error).__name__, check=check.__name__),
                    patch("src.services.user_validation_service.requests") as requests,
                ):
                    requests.post.side_effect = error

                    with self.assertRaises(ServiceUnavailableException):
                        check(
                            email="me@ruchij.com",
                            password="secret",
                            deadline=_ample_deadline(),
                        )
