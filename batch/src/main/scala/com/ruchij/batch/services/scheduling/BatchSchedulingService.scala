package com.ruchij.batch.services.scheduling

import cats.data.OptionT
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

trait BatchSchedulingService[F[_]] {

  val acquireTask: OptionT[F, ScheduledVideoDownload]

  val staleTask: OptionT[F, ScheduledVideoDownload]

  def updateSchedulingStatusById(id: String, status: SchedulingStatus): F[ScheduledVideoDownload]

  /**
    * Changes the status only if the current status is one of `from`, and publishes the change. Returns None, without
    * writing anything, if the status is something else or the scheduled video download does not exist.
    */
  def transitionSchedulingStatusById(
    id: String,
    from: Set[SchedulingStatus],
    to: SchedulingStatus
  ): F[Option[ScheduledVideoDownload]]

  /**
    * Records a failed download: schedules a retry with backoff while attempts remain, otherwise sets Error. Only a
    * download that a worker is running can fail, so nothing is written, and None is returned, if the download is
    * not Acquired, Active or Downloaded, such as when it has been completed, paused or deleted since.
    */
  def recordFailure(id: String, throwable: Throwable): F[Option[ScheduledVideoDownload]]

  def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): F[Seq[ScheduledVideoDownload]]

  def completeScheduledVideoDownload(id: String): F[ScheduledVideoDownload]

  def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]]

  def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate]

  def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload]

  def publishScheduledVideoDownload(id: String): F[ScheduledVideoDownload]

  def deleteById(id: String): F[ScheduledVideoDownload]
}
