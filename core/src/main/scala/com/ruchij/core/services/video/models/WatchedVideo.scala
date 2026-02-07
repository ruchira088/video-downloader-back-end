package com.ruchij.core.services.video.models

import com.ruchij.core.daos.video.models.Video
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

case class WatchedVideo(
  userId: String,
  video: Video,
  createdAt: Instant,
  lastUpdatedAt: Instant,
  duration: FiniteDuration
)
