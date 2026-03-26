from mangum import Mangum
from src.main import http_app

handler = Mangum(http_app, lifespan="off")
