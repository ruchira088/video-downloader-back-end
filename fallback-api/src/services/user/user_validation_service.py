from abc import ABC, abstractmethod
from datetime import datetime

import requests
from pyparsing import ParseResults

from src.config.VideoDownloaderConfiguration import VideoDownloaderConfiguration
from src.services.user.user_service import User


class UserValidationService(ABC):
    @abstractmethod
    def get_user(self, email: str, password: str) -> User:
        pass


class VideoDownloaderUserValidationService(UserValidationService):
    def __init__(self, video_downloader_api_url: str):
        self._video_downloader_api_url = video_downloader_api_url

    def get_user(self, email: str, password: str) -> User:
        auth_token = self._authenticate(email, password)
        user = self._logout(auth_token)

        return user

    def _authenticate(self, email: str, password: str) -> str:
        response = requests.post(
            f'{self._video_downloader_api_url}/authentication/login',
            json={
                'email': email,
                'password': password
            }
        )

        response.raise_for_status()

        return response.json().get('secret')

    def _logout(self, auth_token: str) -> User:
        response = requests.delete(
            f'{self._video_downloader_api_url}/authentication/logout',
            headers={
                'Authorization': f'Bearer {auth_token}'
            }
        )

        response.raise_for_status()

        response_body = response.json()
        user_id = response_body['id']
        created_at = response_body['createdAt']
        email = response_body['email']
        first_name = response_body['firstName']
        last_name = response_body['lastName']

        return User(
            id=user_id,
            created_at=datetime.fromisoformat(created_at),
            email=email,
            first_name=first_name,
            last_name=last_name,
        )

def get_user_validation_service(parse_results: ParseResults) -> UserValidationService:
    video_downloader_configuration = VideoDownloaderConfiguration.parse(parse_results)
    user_validation_service = VideoDownloaderUserValidationService(video_downloader_configuration.url)

    return user_validation_service