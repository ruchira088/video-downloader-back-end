from flask import Flask

from src.web.blueprints.schedule_blueprint import schedule_blueprint
from src.web.blueprints.service_blueprint import service_blueprint
from src.web.blueprints.user_blueprint import user_blueprint
from src.web.blueprints.video_blueprint import video_blueprint

app = Flask(__name__)

app.register_blueprint(user_blueprint)
app.register_blueprint(schedule_blueprint)
app.register_blueprint(video_blueprint)
app.register_blueprint(service_blueprint)

if __name__ == '__main__':
    app.run(debug=True)
