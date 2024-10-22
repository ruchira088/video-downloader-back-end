package com.ruchij.core.services.video.models

import com.ruchij.core.daos.video.models.Video
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

case class WatchedVideo(
  userId: String,
  video: Video,
  createdAt: DateTime,
  lastUpdatedAt: DateTime,
  duration: FiniteDuration
)
