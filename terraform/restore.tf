
resource "aws_iam_user" "db_restorer" {
  name = "video-downloader-db-restorer"
}

resource "aws_iam_user_policy" "db_restorer_iam_policy" {
  name = "video-downloader-db-restorer-policy"
  user = aws_iam_user.db_restorer.name

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "s3:Get*",
          "s3:List*"
        ],
        Resource = "${aws_s3_bucket.backup_bucket.arn}/*"
      }
    ]
  })
}

resource "aws_iam_access_key" "db_restorer_access_key" {
  user = aws_iam_user.db_restorer.name
}

resource "aws_ssm_parameter" "db_restorer_access_key_id" {
  name = "/video-downloader/shared/db-restorer/access-key-id"
  type = "SecureString"
  value = aws_iam_access_key.db_restorer_access_key.id
}

resource "aws_ssm_parameter" "db_restorer_secret_access_key" {
  name = "/video-downloader/shared/db-restorer/secret-access-key"
  type = "SecureString"
  value = aws_iam_access_key.db_restorer_access_key.secret
}