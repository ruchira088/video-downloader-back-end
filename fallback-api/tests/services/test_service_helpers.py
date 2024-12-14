from typing import Any

import boto3
from pydantic import BaseModel


class CognitoDetails(BaseModel):
    user_pool_id: str
    user_pool_name: str
    user_pool_client_id: str
    user_pool_client_name: str
    user_pool_client_secret: str
    cognito_client: Any


def setup_cognito(prefix: str) -> CognitoDetails:
    cognito_client = boto3.client("cognito-idp", region_name="ap-northeast-2")

    user_pool_name = f"{prefix}-user-pool"
    user_pool_creation_response = cognito_client.create_user_pool(
        PoolName=user_pool_name,
        Schema=[
            {
                "Name": "user_id",
                "AttributeDataType": "String",
                "Mutable": False,
            }
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
