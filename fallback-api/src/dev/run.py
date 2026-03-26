import uvicorn

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.config.http_configuration import HttpConfiguration
from src.config.video_downloader_configuration import VideoDownloaderConfiguration
from src.dev.containers.CognitoContainer import CognitoContainer
from src.config.configuration import get_config_tree, AppConfiguration
from src.main import create_http_app


def main():
    with CognitoContainer() as cognito_container:
        aws_cognito_configuration: AwsCognitoConfiguration = (
            cognito_container.create_cognito_client()
        )

        config_tree = get_config_tree()
        http_configuration = HttpConfiguration.parse(config_tree)
        video_downloader_configuration = VideoDownloaderConfiguration.parse(config_tree)

        app_configuration: AppConfiguration = AppConfiguration(
            cognito=aws_cognito_configuration,
            http=http_configuration,
            video_downloader=video_downloader_configuration,
        )

        http_app = create_http_app(app_configuration)
        uvicorn.run(
            http_app, host=app_configuration.http.host, port=app_configuration.http.port
        )


if __name__ == "__main__":
    main()
