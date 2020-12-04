package com.ruchij.core.daos.workers.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import org.joda.time.DateTime

case class Worker(
  id: String,
  reservedAt: Option[DateTime],
  taskAssignedAt: Option[DateTime],
  heartBeatAt: Option[DateTime],
  scheduledVideoDownload: Option[ScheduledVideoDownload]
)

object Worker {
  def workerIdFromIndex(index: Int): String =
    "worker-" + (if (index < 10) s"0$index" else index)
}
