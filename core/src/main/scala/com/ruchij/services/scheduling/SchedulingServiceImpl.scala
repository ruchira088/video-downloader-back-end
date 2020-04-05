package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Clock, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.daos.scheduling.SchedulingDao
import fs2.Stream
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.services.video.VideoAnalysisService
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

class SchedulingServiceImpl[F[_]: MonadError[*[_], Throwable]: Timer](
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

  override def updateDownloadProgress(key: String, downloadedBytes: Long): F[Int] =
    for {
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      result <- schedulingDao.updateDownloadProgress(key, downloadedBytes, new DateTime(timestamp))

      _ <- if (result == 0) ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"Key not found: $key"))
      else Applicative[F].unit
    } yield result

  override def completeTask(key: String): F[ScheduledVideoDownload] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .flatMap { timestamp =>
        schedulingDao
          .completeTask(key, new DateTime(timestamp))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    schedulingDao.retrieveNewTask.flatMap { scheduledVideoDownload =>
      schedulingDao.setInProgress(scheduledVideoDownload.videoMetadata.key, inProgress = true)
    }

  override val active: Stream[F, ScheduledVideoDownload] =
    Stream.awakeDelay[F](Duration.create(500, TimeUnit.MILLISECONDS))
      .productR {
        Stream.eval(Clock[F].realTime(TimeUnit.MILLISECONDS)).map(timestamp => new DateTime(timestamp))
      }
      .scan[(Option[DateTime], Option[DateTime])]((None, None)) {
        case ((_, Some(previous)), timestamp) => (Some(previous), Some(timestamp))
        case (_, timestamp) => (None, Some(timestamp))
      }
      .collect {
        case (Some(start), Some(end)) => (start, end)
      }
      .evalMap {
        case (start, end) => schedulingDao.active(start, end)
      }
      .flatMap {
        scheduledDownloads => Stream.emits(scheduledDownloads)
      }
}
