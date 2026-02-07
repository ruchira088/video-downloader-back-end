package com.ruchij.core.daos.videowatchhistory.models

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

case class VideoWatchHistory(
  id: String,
  userId: String,
  videoId: String,
  createdAt: Instant,
  lastUpdatedAt: Instant,
  duration: FiniteDuration
)
