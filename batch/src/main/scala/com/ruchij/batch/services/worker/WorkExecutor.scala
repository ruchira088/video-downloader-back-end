package com.ruchij.batch.services.worker

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.video.models.Video

trait WorkExecutor[F[_]] {
  def execute(scheduledVideoDownload: ScheduledVideoDownload): F[Video]
}