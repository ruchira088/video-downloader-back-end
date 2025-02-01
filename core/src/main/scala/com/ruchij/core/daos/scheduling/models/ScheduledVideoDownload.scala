package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.videometadata.models.VideoMetadata
import org.joda.time.DateTime

final case class ScheduledVideoDownload(
  scheduledAt: DateTime,
  lastUpdatedAt: DateTime,
  status: SchedulingStatus,
  downloadedBytes: Long,
  videoMetadata: VideoMetadata,
  completedAt: Option[DateTime],
  errorInfo: Option[ErrorInfo]
)

object ScheduledVideoDownload {
  case class ErrorInfo(message: String, details: String)
}