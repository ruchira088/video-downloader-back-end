package com.ruchij.core.daos.videowatchhistory.models

import com.ruchij.core.daos.video.models.Video
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

case class DetailedVideoWatchHistory(
  id: String,
  userId: String,
  video: Video,
  createdAt: DateTime,
  lastUpdatedAt: DateTime,
  duration: FiniteDuration
)
