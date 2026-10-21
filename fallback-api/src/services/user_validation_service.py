import logging
import threading
from abc import ABC, abstractmethod
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from urllib.parse import urljoin

import requests
from pydantic import EmailStr, HttpUrl
from requests.exceptions import ConnectionError as RequestsConnectionError
from requests.exceptions import Timeout

from src.services.deadline import Deadline
from src.services.exceptions import ServiceUnavailableException
from src.services.models.user import User, parse_role

logger = logging.getLogger(__name__)

# The most each call to the main API waits, as (connect, read) timeouts: an unreachable or
# unresponsive main API fails a call in at most about 13 s. Each call is also held within the
# request's Deadline, so a slow call leaves the later ones less time rather than running the
# request past API Gateway's or the Lambda's timeout. The read timeout bounds each wait for
# data, not the whole response, but the main API's answers here are a few hundred bytes.
MAIN_API_TIMEOUT_SECONDS = (3, 10)
# A call with less time than this left isn't made, as it could hardly succeed
MIN_CALL_SECONDS = 0.5


def _start_daemon_thread(task: Callable[[], None]) -> None:
    threading.Thread(target=task, daemon=True).start()


class UserValidationService(ABC):
    @abstractmethod
    def get_user(self, email: EmailStr, password: str, deadline: Deadline) -> User:
        """The main API's copy of the user, once it accepts the email and password."""

    @abstractmethod
    def check_credentials(self, email: str, password: str, deadline: Deadline) -> str:
        """The id of the user the main API accepts the email and password for."""


class VideoDownloaderUserValidationService(UserValidationService):
    def __init__(
        self,
        video_downloader_api_url: HttpUrl,
        run_in_background: Callable[[Callable[[], None]], None] = _start_daemon_thread,
    ):
        self._video_downloader_api_url = str(video_downloader_api_url)
        self._run_in_background = run_in_background

    def get_user(self, email: EmailStr, password: str, deadline: Deadline) -> User:
        # The logout answers with the user, so it is waited for
        with _main_api_errors():
            login = self._login(email, password, deadline)
            return self._logout(login["secret"], deadline)

    def check_credentials(self, email: str, password: str, deadline: Deadline) -> str:
        with _main_api_errors():
            login = self._login(email, password, deadline)
            user_id = login.get("userId")
            if not isinstance(user_id, str):
                # A main API that doesn't name the user at login; the logout does
                return self._logout(login["secret"], deadline).id

        # The logout only ends the session the login opened, which otherwise expires on its
        # own, so it doesn't spend the request's time. In Lambda it may not finish: the
        # environment is frozen once the response is sent, and the thread resumes, if at all,
        # in a later invocation, where its call has probably timed out.
        secret = login["secret"]
        self._run_in_background(lambda: self._logout_quietly(secret, deadline))
        return user_id

    def _login(self, email: str, password: str, deadline: Deadline) -> dict:
        response = requests.post(
            urljoin(self._video_downloader_api_url, "authentication/login"),
            json={"email": email, "password": password},
            timeout=_timeout(deadline),
        )

        response.raise_for_status()

        return response.json()

    def _logout(self, auth_token: str, deadline: Deadline) -> User:
        response = requests.delete(
            urljoin(self._video_downloader_api_url, "authentication/logout"),
            headers={"Authorization": f"Bearer {auth_token}"},
            timeout=_timeout(deadline),
        )

        response.raise_for_status()

        response_body = response.json()
        user_id = response_body["id"]
        email = response_body["email"]
        first_name = response_body["firstName"]
        last_name = response_body["lastName"]

        return User(
            id=user_id,
            email=email,
            first_name=first_name,
            last_name=last_name,
            role=parse_role(response_body.get("role")),
        )

    def _logout_quietly(self, auth_token: str, deadline: Deadline) -> None:
        try:
            self._logout(auth_token, deadline)
        except Exception:
            logger.warning(
                "Unable to log out of the main API after checking credentials",
                exc_info=True,
            )


@contextmanager
def _main_api_errors() -> Iterator[None]:
    """Reports a main API that can't be reached in time as unavailable."""
    try:
        yield
    except (Timeout, RequestsConnectionError) as error:
        raise ServiceUnavailableException(
            "The main video downloader API could not be reached"
        ) from error


def _timeout(deadline: Deadline) -> tuple[float, float]:
    remaining = deadline.remaining()
    if remaining < MIN_CALL_SECONDS:
        raise ServiceUnavailableException(
            "The main video downloader API answered too slowly to finish in time"
        )

    connect_timeout, read_timeout = MAIN_API_TIMEOUT_SECONDS
    return min(connect_timeout, remaining), min(read_timeout, remaining)
