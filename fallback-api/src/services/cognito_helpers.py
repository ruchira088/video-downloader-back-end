import hmac
from base64 import b64encode
from collections.abc import Mapping
from hashlib import sha256
from typing import Any

import boto3

from src.config.aws_cognito_configuration import AwsCognitoConfiguration


def create_cognito_client(cognito_configuration: AwsCognitoConfiguration):
    client_args: Mapping[str, Any] = (
        {}
        if cognito_configuration.endpoint_url is None
        else {"endpoint_url": str(cognito_configuration.endpoint_url)}
    )

    return boto3.client("cognito-idp", **client_args)


def get_client_secret(cognito_idp_client, user_pool_id: str, client_id: str) -> str:
    response = cognito_idp_client.describe_user_pool_client(
        UserPoolId=user_pool_id, ClientId=client_id
    )

    return response["UserPoolClient"]["ClientSecret"]


def secret_hash(username: str, client_id: str, client_secret: str) -> str:
    message = bytes(username + client_id, encoding="utf-8")
    key = bytes(client_secret, encoding="utf-8")

    return b64encode(hmac.new(key, message, digestmod=sha256).digest()).decode()
