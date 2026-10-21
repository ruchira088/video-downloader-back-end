import boto3

from src.config.configuration import get_config_tree
from src.config.sync_configuration import SyncConfiguration
from src.sync.sqs_batch import process_sqs_batch
from src.sync.sync_applier import SyncApplier
from src.sync.user_revoker import CognitoUserRevoker

config_tree = get_config_tree()
sync_configuration: SyncConfiguration = SyncConfiguration.parse(config_tree)
sync_applier = SyncApplier(
    boto3.resource("dynamodb").Table(sync_configuration.table_name)
)

# Without a user pool, UserRevocation messages fail, so they are retried and then dead-lettered,
# which raises the alarm, rather than being lost.
user_pool_id: str | None = config_tree.get_string("aws-cognito.user-pool-id", None)
user_revoker = (
    None
    if user_pool_id is None
    else CognitoUserRevoker(boto3.client("cognito-idp"), user_pool_id)
)


def handler(event, context):
    return process_sqs_batch(event, sync_applier, user_revoker)
