from flask import Blueprint

from src.web.decorators.authentication import authenticated

schedule_blueprint = Blueprint('schedule', __name__, url_prefix='/schedule')

@schedule_blueprint.route('/', methods=['POST'])
@authenticated
def schedule_video_download():
    pass