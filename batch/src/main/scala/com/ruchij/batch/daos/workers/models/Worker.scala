package com.ruchij.batch.daos.workers.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.workers.models.WorkerStatus
import java.time.Instant

final case class Worker(
  id: String,
  status: WorkerStatus,
  heartBeatAt: Option[Instant],
  taskAssignedAt: Option[Instant],
  scheduledVideoDownload: Option[ScheduledVideoDownload],
  owner: Option[String]
)

object Worker {
  def workerIdFromIndex(index: Int): String =
    "worker-" + (if (index < 10) s"0$index" else index)
}
