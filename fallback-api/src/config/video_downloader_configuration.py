from pydantic import BaseModel, HttpUrl
from pyparsing import ParseResults


class VideoDownloaderConfiguration(BaseModel):
    url: HttpUrl

    @classmethod
    def parse(cls, parse_results: ParseResults) -> "VideoDownloaderConfiguration":
        config = parse_results["video-downloader"]
        url: HttpUrl = config["url"]

        video_downloader_configuration = VideoDownloaderConfiguration(url=url)
        return video_downloader_configuration
