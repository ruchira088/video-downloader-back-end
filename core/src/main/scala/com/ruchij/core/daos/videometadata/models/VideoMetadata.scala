package com.ruchij.core.daos.videometadata.models

import com.ruchij.core.daos.resource.models.FileResource
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

final case class VideoMetadata(
  url: Uri,
  id: String,
  videoSite: VideoSite,
  title: String,
  duration: FiniteDuration,
  size: Long,
  thumbnail: FileResource
)
