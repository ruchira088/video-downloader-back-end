from datetime import datetime

from flask import Blueprint, jsonify


def service_blueprint() -> Blueprint:
    blueprint = Blueprint("service", __name__, url_prefix="/service")

    @blueprint.get("/info")
    def info():
        return jsonify(
            {
                "service_name": "video-downloader-fallback-api",
                "timestamp": datetime.now().isoformat(),
            }
        )

    return blueprint
