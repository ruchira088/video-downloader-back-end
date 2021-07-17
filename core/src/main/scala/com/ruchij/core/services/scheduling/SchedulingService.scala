package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.models.DurationRange
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

trait SchedulingService[F[_]] {
  def schedule(uri: Uri): F[ScheduledVideoDownload]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]]
  ): F[Seq[ScheduledVideoDownload]]

  def getById(id: String): F[ScheduledVideoDownload]

  def completeTask(id: String): F[ScheduledVideoDownload]

  def updateStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload]

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val staleTask: OptionT[F, ScheduledVideoDownload]

  def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]]

  def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload]

  def subscribeToUpdates(groupId: String): Stream[F, ScheduledVideoDownload]

  def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  def subscribeToDownloadProgress(groupId: String): Stream[F, DownloadProgress]
}
