
resource "aws_s3_bucket" "assets_bucket" {
  bucket = "assets.video-downloader.ruchij.com"
}

resource "aws_s3_bucket_object" "placeholder_image" {
  bucket = aws_s3_bucket.assets_bucket.bucket
  key = "video-placeholder.png"

  source = "./assets/video-placeholder.png"
  etag = filemd5("./assets/video-placeholder.png")
  acl = "public-read"
  content_type = "image/png"
}