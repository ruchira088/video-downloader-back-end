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

  def setErrorById(id: String, throwable: Throwable): F[ScheduledVideoDownload]

  def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): F[Seq[ScheduledVideoDownload]]

  def completeScheduledVideoDownload(id: String): F[ScheduledVideoDownload]

  def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]]

  def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit]

  def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate]

  def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload]

  def publishScheduledVideoDownload(id: String): F[ScheduledVideoDownload]

}
