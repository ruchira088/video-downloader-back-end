from abc import ABC, abstractmethod


class VideoService(ABC):
    @abstractmethod
    def video_metadata(self, video_url: str):
        pass
