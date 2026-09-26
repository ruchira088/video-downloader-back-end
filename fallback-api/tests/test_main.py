import unittest

from fastapi.testclient import TestClient
from moto import mock_aws

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.config.configuration import AppConfiguration
from src.config.http_configuration import HttpConfiguration
from src.config.sync_configuration import SyncConfiguration
from src.config.video_downloader_configuration import VideoDownloaderConfiguration
from src.main import create_http_app
from tests.services.test_service_helpers import setup_cognito


def app_configuration_for_tests(user_pool_id: str, client_id: str) -> AppConfiguration:
    return AppConfiguration(
        cognito=AwsCognitoConfiguration(user_pool_id=user_pool_id, client_id=client_id),
        http=HttpConfiguration(host="0.0.0.0", port=8000, debug=False),
        video_downloader=VideoDownloaderConfiguration(url="https://api.example.com"),
        sync=SyncConfiguration(
            table_name="scheduled-videos",
            fallback_to_main_queue_url="https://sqs.ap-southeast-2.amazonaws.com/000000000000/q",
        ),
    )


@mock_aws
class TestCreateHttpApp(unittest.TestCase):
    def test_app_exposes_the_expected_routes(self):
        cognito_details = setup_cognito(__name__)

        app = create_http_app(
            app_configuration_for_tests(
                cognito_details.user_pool_id, cognito_details.user_pool_client_id
            )
        )

        # The OpenAPI schema lists every route, however its router was included
        paths = set(app.openapi()["paths"])
        self.assertTrue(
            {
                "/user",
                "/authentication/login",
                "/authentication/logout",
                "/schedule",
                "/service/info",
            }
            <= paths
        )

    def test_the_web_app_origins_may_call_the_api_from_a_browser(self):
        cognito_details = setup_cognito(__name__)
        client = TestClient(
            create_http_app(
                app_configuration_for_tests(
                    cognito_details.user_pool_id, cognito_details.user_pool_client_id
                )
            )
        )

        def preflight(origin: str):
            return client.options(
                "/user",
                headers={
                    "Origin": origin,
                    "Access-Control-Request-Method": "POST",
                    "Access-Control-Request-Headers": "content-type",
                },
            )

        for origin in [
            "https://videos.ruchij.com",
            "https://staging.videos.ruchij.com",
            "https://my-branch.videos.ruchij.com",
            "http://localhost:5173",
            "http://192.168.1.20:5173",
        ]:
            with self.subTest(origin=origin):
                response = preflight(origin)
                self.assertEqual(response.status_code, 200)
                self.assertEqual(
                    response.headers["access-control-allow-origin"], origin
                )

        for origin in [
            "https://evil.com",
            "https://evilruchij.com",
            "https://videos.ruchij.com.evil.com",
        ]:
            with self.subTest(origin=origin):
                response = preflight(origin)
                self.assertEqual(response.status_code, 400)
                self.assertNotIn("access-control-allow-origin", response.headers)
