package com.ruchij.daos.scheduling

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.http4s.Uri
import org.joda.time.DateTime

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def updateDownloadProgress(url: Uri, downloadedBytes: Long, timestamp: DateTime): F[Int]
}
