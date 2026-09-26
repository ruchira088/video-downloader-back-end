from abc import ABC, abstractmethod
from urllib.parse import urljoin

import requests
from pydantic import EmailStr, HttpUrl
from requests.exceptions import ConnectionError as RequestsConnectionError
from requests.exceptions import Timeout

from src.services.exceptions import ServiceUnavailableException
from src.services.models.user import User, parse_role

# (connect, read) timeouts. The read timeout bounds each wait for data, not the whole response,
# so an unreachable or unresponsive main API fails a call in at most about 13 s, and the two calls
# a sign-up makes in about 26 s: just under API Gateway's 29 s and the Lambda's 30 s timeouts. A
# main API trickling out a response slower still could run past them.
MAIN_API_TIMEOUT_SECONDS = (3, 10)


class UserValidationService(ABC):
    @abstractmethod
    def get_user(self, email: EmailStr, password: str) -> User:
        pass


class VideoDownloaderUserValidationService(UserValidationService):
    def __init__(self, video_downloader_api_url: HttpUrl):
        self._video_downloader_api_url = str(video_downloader_api_url)

    def get_user(self, email: EmailStr, password: str) -> User:
        try:
            auth_token = self._authenticate(email, password)
            return self._logout(auth_token)
        except (Timeout, RequestsConnectionError) as error:
            raise ServiceUnavailableException(
                "The main video downloader API could not be reached"
            ) from error

    def _authenticate(self, email: EmailStr, password: str) -> str:
        response = requests.post(
            urljoin(self._video_downloader_api_url, "authentication/login"),
            json={"email": email, "password": password},
            timeout=MAIN_API_TIMEOUT_SECONDS,
        )

        response.raise_for_status()

        return response.json().get("secret")

    def _logout(self, auth_token: str) -> User:
        response = requests.delete(
            urljoin(self._video_downloader_api_url, "authentication/logout"),
            headers={"Authorization": f"Bearer {auth_token}"},
            timeout=MAIN_API_TIMEOUT_SECONDS,
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
