package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.daos.videometadata.models.VideoMetadata
import org.joda.time.DateTime

case class ScheduledVideoDownload(
  scheduledAt: DateTime,
  lastUpdatedAt: DateTime,
  status: SchedulingStatus,
  videoMetadata: VideoMetadata,
  completedAt: Option[DateTime]
)

object ScheduledVideoDownload {
  trait Progress[+A] {
    val progress: A
  }
}