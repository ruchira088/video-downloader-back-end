from pydantic import BaseModel
from pyhocon import ConfigTree


class SyncConfiguration(BaseModel):
    table_name: str
    fallback_to_main_queue_url: str

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "SyncConfiguration":
        sync_config: ConfigTree = config_tree["sync"]

        return SyncConfiguration(
            table_name=sync_config["table-name"],
            fallback_to_main_queue_url=sync_config["fallback-to-main-queue-url"],
        )
