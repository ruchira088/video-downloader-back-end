import uvicorn

from src.config.configuration import get_config, AppConfiguration
from src.main import create_http_app


def main():
    app_configuration: AppConfiguration = AppConfiguration.parse(get_config())
    http_app = create_http_app(app_configuration)
    uvicorn.run(http_app, host=app_configuration.http.host, port=app_configuration.http.port)


if __name__ == "__main__":
    main()
