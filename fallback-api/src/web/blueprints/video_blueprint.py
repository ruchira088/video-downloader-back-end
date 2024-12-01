from flask import Blueprint


def video_blueprint() -> Blueprint:
    blueprint = Blueprint("video", __name__, url_prefix="/video")
    return blueprint
