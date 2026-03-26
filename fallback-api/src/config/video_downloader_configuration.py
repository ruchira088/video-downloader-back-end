from pydantic import BaseModel, HttpUrl
from pyhocon import ConfigTree


class VideoDownloaderConfiguration(BaseModel):
    url: HttpUrl

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "VideoDownloaderConfiguration":
        video_downloader_config: ConfigTree = config_tree["video-downloader"]
        url: HttpUrl = video_downloader_config["url"]

        video_downloader_configuration = VideoDownloaderConfiguration(url=url)
        return video_downloader_configuration
