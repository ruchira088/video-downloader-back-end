package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.Clock
import cats.implicits._
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.services.video.VideoAnalysisService
import org.http4s.Uri
import org.joda.time.DateTime

class SchedulingServiceImpl[F[_]: Monad: Clock](videoAnalysisService: VideoAnalysisService[F], schedulingDao: SchedulingDao[F])
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      videoMetadata <- videoAnalysisService.metadata(uri)
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS).map(milliseconds => new DateTime(milliseconds))

      scheduledVideoDownload = ScheduledVideoDownload(timestamp, timestamp, false, videoMetadata, 0, None)
      _ <- schedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload

  override def updateDownloadProgress(url: Uri, downloadedBytes: Long): F[Int] =
    for {
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      result <- schedulingDao.updateDownloadProgress(url, downloadedBytes, new DateTime(timestamp))
    }
    yield result
}
