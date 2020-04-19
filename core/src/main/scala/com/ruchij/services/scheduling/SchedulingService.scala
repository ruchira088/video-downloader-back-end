package com.ruchij.services.scheduling

import cats.data.OptionT
import fs2.Stream
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.http4s.Uri

trait SchedulingService[F[_]] {
  def schedule(uri: Uri): F[ScheduledVideoDownload]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[ScheduledVideoDownload]]

  def updateDownloadProgress(key: String, downloadedBytes: Long): F[Int]

  def completeTask(key: String): F[ScheduledVideoDownload]

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val active: Stream[F, ScheduledVideoDownload]
}
