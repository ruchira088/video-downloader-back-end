
resource "aws_s3_bucket" "backup_bucket" {
  bucket = "backups.video-downloader.ruchij.com"
}

resource "aws_iam_user" "backup_creator" {
  name = "video-downloader-backup-creator"
}

resource "aws_iam_user_policy" "backup_creator_iam_policy" {
  name = "video-downloader-backup-creator-policy"
  user = aws_iam_user.backup_creator.name

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "s3:PutObject"
        ],
        Resource = "${aws_s3_bucket.backup_bucket.arn}/*"
      }
    ]
  })
}

resource "aws_iam_access_key" "backup_creator_access_key" {
  user = aws_iam_user.backup_creator.name
}

resource "aws_ssm_parameter" "backup_creator_access_key_id" {
  name = "/video-downloader/shared/backup-creator/access-key-id"
  type = "SecureString"
  value = aws_iam_access_key.backup_creator_access_key.id
}

resource "aws_ssm_parameter" "backup_creator_secret_access_key" {
  name = "/video-downloader/shared/backup-creator/secret-access-key"
  type = "SecureString"
  value = aws_iam_access_key.backup_creator_access_key.secret
}