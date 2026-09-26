
# Everything the main API needs for fallback sync with each fallback-api stack (see fallback-api/template.yaml), in
# one secret per stage that the deploy playbooks read: access keys for the IAM user the stack creates for the main
# API (the stack creates the user but not its keys), and the stack's queue URLs and table name. Apply this once both
# stacks have been deployed with these outputs: the lookup below fails until then.
locals {
  fallback_sync_stages = toset(["staging", "prod"])
}

data "aws_cloudformation_stack" "fallback_api" {
  for_each = local.fallback_sync_stages

  name = "${each.key}-fallback-video-api"
}

resource "aws_iam_access_key" "fallback_sync_access_key" {
  for_each = local.fallback_sync_stages

  user = data.aws_cloudformation_stack.fallback_api[each.key].outputs["MainSideSyncUserName"]
}

resource "aws_secretsmanager_secret" "fallback_sync" {
  for_each = local.fallback_sync_stages

  name        = "video-downloader/${each.key}/fallback-sync"
  description = "AWS credentials and resources the ${each.key} main API uses for fallback sync"
}

resource "aws_secretsmanager_secret_version" "fallback_sync" {
  for_each = local.fallback_sync_stages

  secret_id = aws_secretsmanager_secret.fallback_sync[each.key].id
  secret_string = jsonencode({
    AWS_ACCESS_KEY_ID                        = aws_iam_access_key.fallback_sync_access_key[each.key].id
    AWS_SECRET_ACCESS_KEY                    = aws_iam_access_key.fallback_sync_access_key[each.key].secret
    FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL = data.aws_cloudformation_stack.fallback_api[each.key].outputs["MainToFallbackQueueUrl"]
    FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL = data.aws_cloudformation_stack.fallback_api[each.key].outputs["FallbackToMainQueueUrl"]
    FALLBACK_SYNC_TABLE_NAME                 = data.aws_cloudformation_stack.fallback_api[each.key].outputs["ScheduledVideosTableName"]
  })
}
