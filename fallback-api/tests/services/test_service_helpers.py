from typing import Any

import boto3
from jwt import PyJWKSet
from moto.utilities.utils import load_resource
from pydantic import BaseModel

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.services.access_token_verifier import CognitoAccessTokenVerifier


class CognitoDetails(BaseModel):
    user_pool_id: str
    user_pool_name: str
    user_pool_client_id: str
    user_pool_client_name: str
    user_pool_client_secret: str
    cognito_client: Any


def setup_cognito(prefix: str) -> CognitoDetails:
    cognito_client = boto3.client("cognito-idp", region_name="ap-southeast-2")

    user_pool_name = f"{prefix}-user-pool"
    user_pool_creation_response = cognito_client.create_user_pool(
        PoolName=user_pool_name,
        Schema=[
            {
                "Name": "user_id",
                "AttributeDataType": "String",
                "Mutable": False,
            },
            {
                "Name": "role",
                "AttributeDataType": "String",
                "Mutable": True,
            },
        ],
    )
    user_pool_id = user_pool_creation_response["UserPool"]["Id"]

    user_pool_client_name = f"{prefix}-user-pool-client"
    user_pool_client_creation_response = cognito_client.create_user_pool_client(
        UserPoolId=user_pool_id, ClientName=user_pool_client_name, GenerateSecret=True
    )

    user_pool_client_id = user_pool_client_creation_response["UserPoolClient"][
        "ClientId"
    ]

    user_pool_client_secret = user_pool_client_creation_response["UserPoolClient"][
        "ClientSecret"
    ]

    cognito_details = CognitoDetails(
        cognito_client=cognito_client,
        user_pool_id=user_pool_id,
        user_pool_name=user_pool_name,
        user_pool_client_id=user_pool_client_id,
        user_pool_client_name=user_pool_client_name,
        user_pool_client_secret=user_pool_client_secret,
    )

    return cognito_details


def moto_access_token_verifier(
    cognito_details: CognitoDetails,
) -> CognitoAccessTokenVerifier:
    """Verifies the access tokens moto issues, which it signs with its own bundled key."""
    configuration = AwsCognitoConfiguration(
        user_pool_id=cognito_details.user_pool_id,
        client_id=cognito_details.user_pool_client_id,
    )
    moto_jwks = load_resource("cognitoidp/resources/jwks-public.json")
    signing_key = PyJWKSet.from_dict(moto_jwks).keys[0].key

    return CognitoAccessTokenVerifier(
        issuer=configuration.token_issuer(),
        client_id=configuration.client_id,
        signing_key_resolver=lambda token: signing_key,
    )


def setup_sqs(queue_name: str) -> tuple[Any, str]:
    sqs_client = boto3.client("sqs", region_name="ap-southeast-2")
    queue_url = sqs_client.create_queue(QueueName=queue_name)["QueueUrl"]

    return sqs_client, queue_url


def setup_dynamodb(table_name: str):
    dynamodb = boto3.resource("dynamodb", region_name="ap-southeast-2")

    return dynamodb.create_table(
        TableName=table_name,
        BillingMode="PAY_PER_REQUEST",
        KeySchema=[
            {"AttributeName": "PK", "KeyType": "HASH"},
            {"AttributeName": "SK", "KeyType": "RANGE"},
        ],
        AttributeDefinitions=[
            {"AttributeName": name, "AttributeType": "S"}
            for name in ["PK", "SK", "GSI1PK", "GSI1SK"]
        ],
        GlobalSecondaryIndexes=[
            {
                "IndexName": "GSI1",
                "KeySchema": [
                    {"AttributeName": "GSI1PK", "KeyType": "HASH"},
                    {"AttributeName": "GSI1SK", "KeyType": "RANGE"},
                ],
                "Projection": {"ProjectionType": "ALL"},
            }
        ],
    )
