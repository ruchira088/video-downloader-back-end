from abc import ABC, abstractmethod


class SchedulingService(ABC):
    @abstractmethod
    def schedule(self, video_url: str, user_id: str):
        pass

    @abstractmethod
    def search(self, video_url: str, user_id: str):
        pass
