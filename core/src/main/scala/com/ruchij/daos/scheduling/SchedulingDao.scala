package com.ruchij.daos.scheduling

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.services.models.{Order, SortBy}
import org.joda.time.DateTime

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Int]

  def getById(id: String): F[Option[ScheduledVideoDownload]]

  def completeTask(id: String, timestamp: DateTime): F[Option[ScheduledVideoDownload]]

  def active(after: DateTime, before: DateTime): F[Seq[ScheduledVideoDownload]]

  def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order): F[Seq[ScheduledVideoDownload]]

  def retrieveNewTask(timestamp: DateTime): F[Option[ScheduledVideoDownload]]
}
