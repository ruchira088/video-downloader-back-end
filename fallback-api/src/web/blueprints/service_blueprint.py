from flask import Blueprint, Response, jsonify

service_blueprint = Blueprint('service', __name__, url_prefix='/service')

@service_blueprint.route('/info')
def info():
    return jsonify({
        'status': 'ok',
    })