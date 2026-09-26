from pydantic import BaseModel, HttpUrl
from pyhocon import ConfigTree


class AwsCognitoConfiguration(BaseModel):
    user_pool_id: str
    client_id: str
    endpoint_url: HttpUrl | None = None
    # Only for local emulators, whose tokens carry an issuer of their own.
    issuer: str | None = None

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "AwsCognitoConfiguration":
        aws_cognito_config: ConfigTree = config_tree["aws-cognito"]

        user_pool_id = aws_cognito_config["user-pool-id"]
        client_id: str = aws_cognito_config["client-id"]
        endpoint_url: str | None = aws_cognito_config.get("endpoint-url", None)
        issuer: str | None = aws_cognito_config.get("issuer", None)

        aws_cognito_configuration = AwsCognitoConfiguration(
            user_pool_id=user_pool_id,
            client_id=client_id,
            endpoint_url=endpoint_url,
            issuer=issuer,
        )
        return aws_cognito_configuration

    def region(self) -> str:
        # User pool ids are "<region>_<suffix>", e.g. "ap-southeast-2_AbCdEf123".
        return self.user_pool_id.split("_", 1)[0]

    def token_issuer(self) -> str:
        if self.issuer is not None:
            return self.issuer

        return f"https://cognito-idp.{self.region()}.amazonaws.com/{self.user_pool_id}"

    def jwks_url(self) -> str:
        if self.endpoint_url is None:
            base_url = self.token_issuer()
        else:
            base_url = f"{str(self.endpoint_url).rstrip('/')}/{self.user_pool_id}"

        return f"{base_url}/.well-known/jwks.json"
