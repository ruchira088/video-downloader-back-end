package com.ruchij.daos.scheduling

import cats.data.OptionT
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.http4s.Uri
import org.joda.time.DateTime

trait SchedulingDao[F[_]] {
  def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int]

  def updateDownloadProgress(url: Uri, downloadedBytes: Long, timestamp: DateTime): F[Int]

  def getByUrl(url: Uri): OptionT[F, ScheduledVideoDownload]

  def setInProgress(url: Uri, inProgress: Boolean): OptionT[F, ScheduledVideoDownload]

  def completeTask(url: Uri, timestamp: DateTime): OptionT[F, ScheduledVideoDownload]

  def activeDownloads(timestamp: DateTime): F[Seq[ScheduledVideoDownload]]

  val retrieveNewTask: OptionT[F, ScheduledVideoDownload]
}
