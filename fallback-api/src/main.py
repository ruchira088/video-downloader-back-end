from flask import Flask

from src.web.blueprints.schedule_blueprint import schedule_blueprint
from src.web.blueprints.user_blueprint import user_blueprint
from src.web.blueprints.video_blueprint import video_blueprint


def main():
    app = Flask(__name__)
    app.register_blueprint(user_blueprint)
    app.register_blueprint(schedule_blueprint)
    app.register_blueprint(video_blueprint)

    app.run(debug=True)


if __name__ == '__main__':
    main()
