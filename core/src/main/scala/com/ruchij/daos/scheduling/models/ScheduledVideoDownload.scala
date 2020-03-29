package com.ruchij.daos.scheduling.models

import com.ruchij.daos.videometadata.models.VideoMetadata
import org.joda.time.DateTime

case class ScheduledVideoDownload(
  scheduledAt: DateTime,
  lastUpdatedAt: DateTime,
  inProgress: Boolean,
  videoMetadata: VideoMetadata,
  downloadedBytes: Long,
  completedAt: Option[DateTime]
)