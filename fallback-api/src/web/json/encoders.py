from datetime import datetime
from json import JSONEncoder


class DateTimeJsonEncoder(JSONEncoder):
    def default(self, obj):
        if isinstance(obj, datetime):
            return obj.isoformat()

        return super().default(obj)


class CompositeJsonEncoder(JSONEncoder):
    def __init__(self, *json_encoders: JSONEncoder):
        super().__init__()
        self._json_encoders = json_encoders

    def default(self, obj):
        for json_encoder in self._json_encoders:
            try:
                return json_encoder.default(obj)
            except TypeError:
                pass

            return super().default(obj)


composite_json_encoder = CompositeJsonEncoder(DateTimeJsonEncoder())
