from typing import Optional

from pydantic import BaseModel, HttpUrl
from pyparsing import ParseResults


class AwsCognitoConfiguration(BaseModel):
    user_pool_id: str
    client_id: str
    endpoint_url: Optional[HttpUrl] = None

    @classmethod
    def parse(cls, parse_results: ParseResults) -> "AwsCognitoConfiguration":
        config = parse_results.get("aws-cognito")

        user_pool_id = config["user-pool-id"]
        client_id: str = config["client-id"]
        endpoint_url: Optional[str] = config.get("endpoint-url")

        aws_cognito_configuration = AwsCognitoConfiguration(
            user_pool_id=user_pool_id, client_id=client_id, endpoint_url=endpoint_url
        )
        return aws_cognito_configuration
