package com.ruchij.daos.videometadata.models

import org.http4s.{MediaType, Uri}

import scala.concurrent.duration.FiniteDuration

case class VideoMetadata(
  url: Uri,
  key: String,
  videoSite: VideoSite,
  title: String,
  duration: FiniteDuration,
  size: Long,
  mediaType: MediaType,
  thumbnail: String
)