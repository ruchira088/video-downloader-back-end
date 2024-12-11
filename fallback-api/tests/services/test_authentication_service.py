import unittest
from datetime import datetime, UTC
from typing import Union
from unittest.mock import MagicMock

import boto3
from moto import mock_aws

from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_service import UserService, CognitoUserService
from src.services.user_validation_service import UserValidationService

sample_user: User = User(
    id="my-user-id",
    created_at=datetime.now(UTC),
    email="me@ruchij.com",
    first_name="John",
    last_name="Doe",
)

sample_password: str = "cam2QGH8eht!vbz1nrh"


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        cognito_client = boto3.client("cognito-idp", region_name="ap-northeast-2")
        user_pool_creation_response = cognito_client.create_user_pool(
            PoolName=f"{__name__}-user-pool"
        )
        user_pool_id = user_pool_creation_response["UserPool"]["Id"]

        user_pool_client_creation_response = cognito_client.create_user_pool_client(
            UserPoolId=user_pool_id, ClientName=f"{__name__}-api-client"
        )

        user_pool_client_id = user_pool_client_creation_response["UserPoolClient"][
            "ClientId"
        ]
        self.user_validation_service: Union[UserValidationService, MagicMock] = (
            MagicMock()
        )

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=cognito_client,
            cognito_user_pool_client_id=user_pool_client_id,
        )

    def test_create_user(self):
        self.user_validation_service.get_user.return_value = sample_user

        created_user: User = self.user_service.create_user(
            email=sample_user.email, password=sample_password
        )

        self.user_validation_service.get_user.assert_called_with(
            email=sample_user.email, password=sample_password
        )
        self.assertIs(sample_user, created_user)

    def test_creating_duplicate_user_throws_resource_conflict_exception(self):
        self.user_validation_service.get_user.return_value = sample_user

        self.user_service.create_user(email=sample_user.email, password=sample_password)

        with self.assertRaises(ResourceConflictException):
            self.user_service.create_user(
                email=sample_user.email, password=sample_password
            )
