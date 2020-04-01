package com.ruchij.services.worker

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.video.models.Video

trait WorkExecutor[F[_]] {
  def execute(scheduledVideoDownload: ScheduledVideoDownload): F[Video]
}