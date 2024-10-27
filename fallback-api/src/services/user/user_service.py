from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime

import requests

from src.config.AwsCognitoConfiguration import AwsCognitoConfiguration
from src.config.VideoDownloaderConfiguration import VideoDownloaderConfiguration


@dataclass
class User:
    id: str
    created_at: datetime
    email: str
    first_name: str
    last_name: str


class UserValidationService(ABC):
    @abstractmethod
    def get_user(self, email: str, password: str) -> User:
        pass


class VideoDownloaderUserValidationService(UserValidationService):
    def __init__(self, video_downloader_configuration: VideoDownloaderConfiguration):
        self._video_downloader_configuration = video_downloader_configuration

    def get_user(self, email: str, password: str) -> User:
        auth_token = self._authenticate(email, password)
        user = self._logout(auth_token)

        return user

    def _authenticate(self, email: str, password: str) -> str:
        response = requests.post(
            f'{self._video_downloader_configuration.url}/authentication/login',
            json={
                'email': email,
                'password': password
            }
        )

        response.raise_for_status()

        return response.json().get('secret')

    def _logout(self, auth_token: str) -> User:
        response = requests.delete(
            f'{self._video_downloader_configuration.url}/authentication/logout',
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


class UserService(ABC):
    @abstractmethod
    def create_user(self, email: str, password: str) -> User:
        pass


class CognitoUserService(UserService):
    def __init__(
            self,
            user_validation_service: UserValidationService,
            aws_cognito_configuration: AwsCognitoConfiguration,
            cognito_idp_client
    ):
        self._cognito_idp_client = cognito_idp_client
        self._user_validation_service = user_validation_service
        self._aws_cognito_configuration = aws_cognito_configuration

    def create_user(self, email: str, password: str) -> User:
        user = self._user_validation_service.get_user(email, password)

        response = self._cognito_idp_client.sign_up(
            ClientId=self._aws_cognito_configuration.client_id,
            Username=email,
            Password=password,
            UserAttributes=[
                {
                    'Name': 'email',
                    'Value': email,
                },
                {
                    'Name': 'firstName',
                    'Value': user.first_name,
                },
                {
                    'Name': 'lastName',
                    'Value': user.last_name
                }
            ]
        )

        return user
