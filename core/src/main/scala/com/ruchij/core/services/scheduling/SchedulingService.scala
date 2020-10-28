package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
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

  def getById(id: String): F[ScheduledVideoDownload]

  def completeTask(id: String): F[ScheduledVideoDownload]

  def updateStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload]

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val updates: Stream[F, ScheduledVideoDownload]

  def updateDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  val downloadProgress: Stream[F, DownloadProgress]
}
