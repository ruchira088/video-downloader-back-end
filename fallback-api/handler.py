from mangum import Mangum

from src.config.configuration import get_config_tree, AppConfiguration
from src.main import create_http_app

app_configuration: AppConfiguration = AppConfiguration.parse(get_config_tree())
http_app = create_http_app(app_configuration)

handler = Mangum(http_app, lifespan="off")
