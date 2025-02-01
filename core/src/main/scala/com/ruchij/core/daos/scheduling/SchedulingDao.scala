package com.ruchij.core.daos.scheduling

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def getById(id: String, maybeUserId: Option[String]): F[Option[ScheduledVideoDownload]]

  def markScheduledVideoDownloadAsComplete(id: String, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateSchedulingStatusById(
    id: String,
    status: SchedulingStatus,
    timestamp: DateTime
  ): F[Option[ScheduledVideoDownload]]

  def setErrorById(id: String, throwable: Throwable, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): F[Seq[ScheduledVideoDownload]]

  def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def deleteById(id: String): F[Int]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): F[Seq[ScheduledVideoDownload]]

  def staleTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateTimedOutTasks(timeout: FiniteDuration, timestamp: DateTime): F[Seq[ScheduledVideoDownload]]

  def acquireTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]
}

object SchedulingDao {
  def notFound(id: String): ResourceNotFoundException =
    ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id")
}
