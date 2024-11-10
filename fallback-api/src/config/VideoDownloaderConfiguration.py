from dataclasses import dataclass

from pyparsing import ParseResults


@dataclass
class VideoDownloaderConfiguration:
    url: str

    @classmethod
    def parse(cls, parse_results: ParseResults) -> 'VideoDownloaderConfiguration':
        config = parse_results['video-downloader']
        url: str = config['url']

        video_downloader_configuration = VideoDownloaderConfiguration(url)
        return video_downloader_configuration