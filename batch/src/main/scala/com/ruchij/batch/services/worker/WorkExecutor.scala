package com.ruchij.batch.services.worker

import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.video.models.Video
import fs2.Stream

trait WorkExecutor[F[_]] {
  def execute(
    scheduledVideoDownload: ScheduledVideoDownload,
    worker: Worker,
    interrupt: Stream[F, Boolean],
    retries: Int
  ): F[Video]
}
