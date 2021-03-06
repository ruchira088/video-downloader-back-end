package com.ruchij.core.daos.scheduling

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.Uri
import org.joda.time.DateTime

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def getById(id: String): F[Option[ScheduledVideoDownload]]

  def completeTask(id: String, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updateStatus(id: String, status: SchedulingStatus, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def updatedDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def deleteById(id: String): F[Option[ScheduledVideoDownload]]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]]
  ): F[Seq[ScheduledVideoDownload]]

  def staleTasks(timestamp: DateTime): F[Seq[ScheduledVideoDownload]]

  def acquireTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]
}
