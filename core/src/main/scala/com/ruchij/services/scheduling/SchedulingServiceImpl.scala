package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.Clock
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.services.video.VideoAnalysisService
import org.http4s.Uri
import org.joda.time.DateTime

class SchedulingServiceImpl[F[_]: MonadError[*[_], Throwable]: Clock](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[F]
) extends SchedulingService[F] {

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

      _ <- if (result == 0) ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"Url not found: $url"))
      else Applicative[F].unit
    } yield result

  override def completeTask(url: Uri): F[ScheduledVideoDownload] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .flatMap { timestamp =>
        schedulingDao
          .completeTask(url, new DateTime(timestamp))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    schedulingDao.retrieveNewTask.flatMap { scheduledVideoDownload =>
      schedulingDao.setInProgress(scheduledVideoDownload.videoMetadata.url, inProgress = true)
    }

  override val activeDownloads: F[Seq[ScheduledVideoDownload]] =
    Clock[F].realTime(TimeUnit.MILLISECONDS)
      .flatMap {
        timestamp => schedulingDao.activeDownloads(new DateTime(timestamp).minusSeconds(30))
      }
}
