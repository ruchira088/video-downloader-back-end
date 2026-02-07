package com.ruchij.core.daos.videowatchhistory.models

import com.ruchij.core.daos.video.models.Video
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

case class DetailedVideoWatchHistory(
  id: String,
  userId: String,
  video: Video,
  createdAt: Instant,
  lastUpdatedAt: Instant,
  duration: FiniteDuration
)
