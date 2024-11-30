from flask import Flask

from src.web.blueprints.schedule_blueprint import schedule_blueprint
from src.web.blueprints.service_blueprint import service_blueprint
from src.web.blueprints.user_blueprint import user_blueprint
from src.web.blueprints.video_blueprint import video_blueprint

def create_http_app():
    app = Flask(__name__)

    app.register_blueprint(user_blueprint)
    app.register_blueprint(schedule_blueprint)
    app.register_blueprint(video_blueprint)
    app.register_blueprint(service_blueprint)

    return app

http_app = create_http_app()

if __name__ == '__main__':
    create_http_app().run(debug=True)
