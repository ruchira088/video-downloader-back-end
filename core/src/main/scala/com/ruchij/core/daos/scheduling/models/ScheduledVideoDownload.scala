package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.videometadata.models.VideoMetadata
import java.time.Instant

final case class ScheduledVideoDownload(
  scheduledAt: Instant,
  lastUpdatedAt: Instant,
  status: SchedulingStatus,
  downloadedBytes: Long,
  videoMetadata: VideoMetadata,
  completedAt: Option[Instant],
  errorInfo: Option[ErrorInfo],
  retryCount: Int = 0,
  retryAfter: Option[Instant] = None
)

object ScheduledVideoDownload {
  case class ErrorInfo(message: String, details: String)
}