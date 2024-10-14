package com.ruchij.core.daos.videowatchhistory.models

import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

case class VideoWatchHistory(
  id: String,
  userId: String,
  videoId: String,
  createdAt: DateTime,
  lastUpdatedAt: DateTime,
  duration: FiniteDuration
)
