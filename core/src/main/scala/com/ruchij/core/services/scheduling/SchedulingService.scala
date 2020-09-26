package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.Stream
import org.http4s.Uri

trait SchedulingService[F[_]] {
  def schedule(uri: Uri): F[ScheduledVideoDownload]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[ScheduledVideoDownload]]

  def updateDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  def getById(id: String): F[ScheduledVideoDownload]

  def completeTask(id: String): F[ScheduledVideoDownload]

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val active: Stream[F, DownloadProgress]
}
