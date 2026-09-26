import unittest

from moto import mock_aws

from src.services.cognito_helpers import get_client_secret
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoHelpers(unittest.TestCase):
    def test_get_client_secret_returns_the_app_client_secret(self):
        cognito_details = setup_cognito(__name__)

        client_secret = get_client_secret(
            cognito_details.cognito_client,
            cognito_details.user_pool_id,
            cognito_details.user_pool_client_id,
        )

        self.assertEqual(client_secret, cognito_details.user_pool_client_secret)
