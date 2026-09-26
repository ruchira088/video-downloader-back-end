import boto3

from src.config.configuration import get_config_tree
from src.config.sync_configuration import SyncConfiguration
from src.sync.sqs_batch import process_sqs_batch
from src.sync.sync_applier import SyncApplier

sync_configuration: SyncConfiguration = SyncConfiguration.parse(get_config_tree())
sync_applier = SyncApplier(
    boto3.resource("dynamodb").Table(sync_configuration.table_name)
)


def handler(event, context):
    return process_sqs_batch(event, sync_applier)
