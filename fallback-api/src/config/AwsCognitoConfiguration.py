from dataclasses import dataclass
from typing import Optional

from pyparsing import ParseResults


@dataclass
class AwsCognitoConfiguration:
    client_id: str
    endpoint_url: Optional[str] = None

    @classmethod
    def parse(cls, parse_results: ParseResults) -> 'AwsCognitoConfiguration':
        config = parse_results.get('aws-cognito')

        client_id: str = config['client-id']
        endpoint_url: Optional[str] = config.get('endpoint-url')

        aws_cognito_configuration = AwsCognitoConfiguration(client_id, endpoint_url)
        return aws_cognito_configuration
