package com.ruchij.daos.workers.models

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.joda.time.DateTime

case class WorkerLock(
  id: String,
  lockAcquiredAt: Option[DateTime],
  scheduledVideoDownload: Option[ScheduledVideoDownload]
)
