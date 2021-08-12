package com.ruchij.batch.daos.workers.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.workers.models.WorkerStatus
import org.joda.time.DateTime

case class Worker(
  id: String,
  status: WorkerStatus,
  heartBeatAt: Option[DateTime],
  taskAssignedAt: Option[DateTime],
  scheduledVideoDownload: Option[ScheduledVideoDownload]
)

object Worker {
  def workerIdFromIndex(index: Int): String =
    "worker-" + (if (index < 10) s"0$index" else index)
}
