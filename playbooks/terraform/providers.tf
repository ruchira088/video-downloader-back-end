provider "aws" {
  region = "ap-southeast-2"
}

terraform {
  backend "s3" {
    bucket = "terraform.ruchij.com"
    key = "video-downloader.tfstate"
    region = "ap-southeast-2"
  }

  required_providers {
    aws = "~> 3.51.0"
  }
}