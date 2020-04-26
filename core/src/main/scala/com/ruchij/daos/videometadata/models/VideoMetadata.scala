package com.ruchij.daos.videometadata.models

import com.ruchij.daos.resource.models.FileResource
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

case class VideoMetadata(
  url: Uri,
  key: String,
  videoSite: VideoSite,
  title: String,
  duration: FiniteDuration,
  size: Long,
  thumbnail: FileResource
)