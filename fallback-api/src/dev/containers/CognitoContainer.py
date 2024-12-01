import boto3
from testcontainers.core.container import DockerContainer
from testcontainers.core.waiting_utils import wait_for_logs


class CognitoContainer(DockerContainer):
    PORT: int = 9229

    def __init__(self, image: str = "jagregory/cognito-local:latest", **kwargs):
        super().__init__(image, **kwargs)
        self.with_exposed_ports(CognitoContainer.PORT)

    @property
    def url(self):
        host = self.get_container_host_ip()
        port = self.get_exposed_port(CognitoContainer.PORT)

        return f"http://{host}:{port}"

    def start(self, timeout: float = 60) -> "CognitoContainer":
        super().start()
        wait_for_logs(self, predicate="Cognito Local running", timeout=timeout)

        return self

    def create_cognito_client(self) -> str:
        cognito_client = boto3.client("cognito-idp", endpoint_url=self.url)

        user_pool_creation_response = cognito_client.create_user_pool(
            PoolName="fallback-api-user-pool"
        )
        user_pool_id = user_pool_creation_response["UserPool"]["Id"]

        user_pool_client_creation_response = cognito_client.create_user_pool_client(
            UserPoolId=user_pool_id, ClientName="fallback-api-client"
        )

        user_pool_client_id = user_pool_client_creation_response["UserPoolClient"][
            "ClientId"
        ]

        return user_pool_client_id
