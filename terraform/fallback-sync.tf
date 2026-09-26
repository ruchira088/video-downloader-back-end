
# Access keys for the IAM user each fallback-api stack creates for the main API's fallback sync (see
# fallback-api/template.yaml). The stack creates the user but not its keys. Apply this once both stacks have been
# deployed with the MainSideSyncUserName output: the lookup below fails until then.
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

resource "aws_secretsmanager_secret" "fallback_sync_aws_credentials" {
  for_each = local.fallback_sync_stages

  name        = "video-downloader/${each.key}/fallback-sync/aws-credentials"
  description = "AWS credentials the ${each.key} main API uses for fallback sync"
}

resource "aws_secretsmanager_secret_version" "fallback_sync_aws_credentials" {
  for_each = local.fallback_sync_stages

  secret_id = aws_secretsmanager_secret.fallback_sync_aws_credentials[each.key].id
  secret_string = jsonencode({
    AWS_ACCESS_KEY_ID     = aws_iam_access_key.fallback_sync_access_key[each.key].id
    AWS_SECRET_ACCESS_KEY = aws_iam_access_key.fallback_sync_access_key[each.key].secret
  })
}
