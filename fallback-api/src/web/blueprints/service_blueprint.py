from datetime import datetime

from flask import Blueprint, jsonify

service_blueprint = Blueprint('service', __name__, url_prefix='/service')

@service_blueprint.route('/info')
def info():
    return jsonify({
        'service_name': 'video-downloader-fallback-api',
        'timestamp': datetime.now().isoformat()
    })