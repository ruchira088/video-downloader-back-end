package com.ruchij.services.video.models

import com.ruchij.daos.videometadata.models.VideoSite
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

case class VideoAnalysisResult(
  url: Uri,
  videoSite: VideoSite,
  title: String,
  duration: FiniteDuration,
  size: Long,
  thumbnail: Uri
)
