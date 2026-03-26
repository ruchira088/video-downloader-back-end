from mangum import Mangum

from config.configuration import get_config, AppConfiguration
from src.main import create_http_app

app_configuration: AppConfiguration = AppConfiguration.parse(get_config())
http_app = create_http_app(app_configuration)

handler = Mangum(http_app, lifespan="off")
