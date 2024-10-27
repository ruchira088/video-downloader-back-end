import unittest

from src.config.VideoDownloaderConfiguration import VideoDownloaderConfiguration
from src.services.user.user_validation_service import VideoDownloaderUserValidationService


class TestVideoDownloaderUserValidationService(unittest.TestCase):
    def setUp(self):
        self.video_downloader_service = VideoDownloaderUserValidationService(
            VideoDownloaderConfiguration('https://api.video.home.ruchij.com')
        )

    def test_should_get_user(self):
        user = self.video_downloader_service.get_user('me@ruchij.com', 'a')
        print(user)
