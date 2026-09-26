import unittest

from src.config.aws_cognito_configuration import AwsCognitoConfiguration


class TestAwsCognitoConfiguration(unittest.TestCase):
    def test_issuer_and_jwks_url_are_derived_from_the_user_pool_id(self):
        configuration = AwsCognitoConfiguration(
            user_pool_id="ap-southeast-2_AbCdEf123", client_id="client-1"
        )

        self.assertEqual(
            configuration.token_issuer(),
            "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123",
        )
        self.assertEqual(
            configuration.jwks_url(),
            "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123"
            "/.well-known/jwks.json",
        )

    def test_an_emulator_serves_the_jwks_from_its_endpoint_with_its_own_issuer(self):
        configuration = AwsCognitoConfiguration(
            user_pool_id="local_abc",
            client_id="client-1",
            endpoint_url="http://localhost:61968",  # type: ignore[arg-type]
            issuer="http://0.0.0.0:9229/local_abc",
        )

        self.assertEqual(configuration.token_issuer(), "http://0.0.0.0:9229/local_abc")
        self.assertEqual(
            configuration.jwks_url(),
            "http://localhost:61968/local_abc/.well-known/jwks.json",
        )
