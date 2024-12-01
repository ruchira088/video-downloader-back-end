from flask import Blueprint

from src.web.decorators.authentication import authenticated


def schedule_blueprint() -> Blueprint:
    blueprint = Blueprint("schedule", __name__, url_prefix="/schedule")

    @blueprint.route("/", methods=["POST"])
    @authenticated
    def schedule_video_download():
        pass

    return blueprint
