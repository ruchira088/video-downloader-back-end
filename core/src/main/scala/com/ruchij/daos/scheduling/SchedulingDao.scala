package com.ruchij.daos.scheduling

import cats.data.OptionT
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.joda.time.DateTime

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def updateDownloadProgress(key: String, downloadedBytes: Long, timestamp: DateTime): F[Int]

  def getByKey(key: String): OptionT[F, ScheduledVideoDownload]

  def setInProgress(key: String, inProgress: Boolean): OptionT[F, ScheduledVideoDownload]

  def completeTask(key: String, timestamp: DateTime): OptionT[F, ScheduledVideoDownload]

  def active(after: DateTime, before: DateTime): F[Seq[ScheduledVideoDownload]]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[ScheduledVideoDownload]]

  val retrieveNewTask: OptionT[F, ScheduledVideoDownload]
}
