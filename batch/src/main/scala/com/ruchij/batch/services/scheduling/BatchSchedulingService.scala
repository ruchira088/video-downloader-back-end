package com.ruchij.batch.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.models.DurationRange
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

trait BatchSchedulingService[F[_]] {

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

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val staleTask: OptionT[F, ScheduledVideoDownload]

  def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload]

  def completeTask(id: String): F[ScheduledVideoDownload]

  def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]]

  def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload]

  def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate]

  def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload]

}
