package com.ruchij.core.daos.scheduling

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.DurationRange
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def getById(id: String): F[Option[ScheduledVideoDownload]]

  def completeTask(id: String, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateStatus(id: String, status: SchedulingStatus, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def deleteById(id: String): F[Option[ScheduledVideoDownload]]

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

  def staleTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateTimedOutTasks(timeout: FiniteDuration, timestamp: DateTime): F[Seq[ScheduledVideoDownload]]

  def acquireTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]
}

object SchedulingDao {
  def notFound(id: String): ResourceNotFoundException =
    ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id")
}
