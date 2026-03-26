from typing import Optional

from pydantic import BaseModel, HttpUrl
from pyhocon import ConfigTree


class AwsCognitoConfiguration(BaseModel):
    user_pool_id: str
    client_id: str
    endpoint_url: Optional[HttpUrl] = None

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "AwsCognitoConfiguration":
        aws_cognito_config: ConfigTree = config_tree["aws-cognito"]

        user_pool_id = aws_cognito_config["user-pool-id"]
        client_id: str = aws_cognito_config["client-id"]
        endpoint_url: Optional[str] = aws_cognito_config.get("endpoint-url", None)

        aws_cognito_configuration = AwsCognitoConfiguration(
            user_pool_id=user_pool_id, client_id=client_id, endpoint_url=endpoint_url
        )
        return aws_cognito_configuration
