package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Clock, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.models.VideoMetadata
import com.ruchij.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.services.download.DownloadService
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.video.VideoAnalysisService
import com.ruchij.services.video.models.VideoAnalysisResult
import fs2.Stream
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

class SchedulingServiceImpl[F[_]: Sync: Timer](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[F],
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  downloadConfiguration: DownloadConfiguration
) extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      VideoAnalysisResult(_, videoSite, title, duration, size, thumbnailUri) <- videoAnalysisService.metadata(uri)
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS).map(milliseconds => new DateTime(milliseconds))

      videoKey <- hashingService.hash(uri.renderString)

      thumbnail <-
        downloadService.download(thumbnailUri, downloadConfiguration.imageFolder)
          .use { downloadResult =>
            downloadResult.data.compile.drain
              .productR(hashingService.hash(thumbnailUri.renderString))
              .map { fileKey =>
                FileResource(fileKey, timestamp, downloadResult.downloadedFileKey, downloadResult.mediaType, downloadResult.size)
              }
          }

      videoMetadata = VideoMetadata(uri, videoKey, videoSite, title, duration, size, thumbnail)

      scheduledVideoDownload = ScheduledVideoDownload(timestamp, timestamp, false, videoMetadata, 0, None)
      _ <- schedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[ScheduledVideoDownload]] =
    schedulingDao.search(term, pageNumber, pageSize)

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[Int] =
    for {
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      result <- schedulingDao.updateDownloadProgress(id, downloadedBytes, new DateTime(timestamp))

      _ <- if (result == 0) ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"ID not found: $id"))
      else Applicative[F].unit
    } yield result

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .flatMap { timestamp =>
        schedulingDao
          .completeTask(id, new DateTime(timestamp))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    schedulingDao.retrieveNewTask.flatMap { scheduledVideoDownload =>
      schedulingDao.setInProgress(scheduledVideoDownload.videoMetadata.id, inProgress = true)
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
