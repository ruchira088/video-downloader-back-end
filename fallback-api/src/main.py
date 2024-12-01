from flask import Flask
from pyparsing import ParseResults

from src.config.Configuration import get_config
from src.config.HttpConfiguration import HttpConfiguration
from src.services.user_service import get_user_service, UserService
from src.web.blueprints.schedule_blueprint import schedule_blueprint
from src.web.blueprints.service_blueprint import service_blueprint
from src.web.blueprints.user_blueprint import user_blueprint
from src.web.blueprints.video_blueprint import video_blueprint


def create_http_app(config: ParseResults) -> Flask:
    app = Flask(__name__)

    user_service: UserService = get_user_service(config)

    app.register_blueprint(user_blueprint(user_service))
    app.register_blueprint(schedule_blueprint())
    app.register_blueprint(video_blueprint())
    app.register_blueprint(service_blueprint())

    return app


parse_results: ParseResults = get_config()
http_app = create_http_app(parse_results)

if __name__ == "__main__":
    http_config: HttpConfiguration = HttpConfiguration.parse(parse_results)
    create_http_app(parse_results).run(
        host=http_config.host, port=http_config.port, debug=http_config.debug
    )
