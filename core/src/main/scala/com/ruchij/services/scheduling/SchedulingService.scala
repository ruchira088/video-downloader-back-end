package com.ruchij.services.scheduling

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.http4s.Uri

trait SchedulingService[F[_]] {
  def schedule(uri: Uri): F[ScheduledVideoDownload]

  def updateDownloadProgress(url: Uri, downloadedBytes: Long): F[Int]
}
