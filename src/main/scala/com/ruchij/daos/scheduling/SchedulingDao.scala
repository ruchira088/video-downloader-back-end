package com.ruchij.daos.scheduling

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]
}
