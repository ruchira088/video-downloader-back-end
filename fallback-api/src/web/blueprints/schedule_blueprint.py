from flask import Blueprint, request, jsonify
from marshmallow import Schema, fields, EXCLUDE

from src.web.decorators.authentication import authenticated

schedule_blueprint = Blueprint('schedule', __name__, url_prefix='/schedule')

class VideoDownloadRequestSchema(Schema):
    videoUrl = fields.URL(required=True)

@schedule_blueprint.post('/')
@authenticated
def schedule_video_download():
    video_download_request = VideoDownloadRequestSchema().load(request.json, unknown=EXCLUDE)
    return jsonify(video_download_request)