package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.VideoMetadataDao
import com.ruchij.daos.videometadata.models.VideoMetadata
import com.ruchij.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.services.download.DownloadService
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.models.{Order, SortBy}
import com.ruchij.services.video.VideoAnalysisService
import com.ruchij.services.video.models.VideoAnalysisResult
import com.ruchij.types.JodaClock
import fs2.Stream
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

class SchedulingServiceImpl[F[_]: Sync: Timer, T[_]: Monad](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  fileResourceDao: FileResourceDao[T],
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: T ~> F)
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      VideoAnalysisResult(_, videoSite, title, duration, size, thumbnailUri) <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      videoKey <- hashingService.hash(uri.renderString)

      thumbnailFileName = thumbnailUri.path.split("/").lastOption.getOrElse("thumbnail.unknown")
      filePath = s"${downloadConfiguration.imageFolder}/$videoKey-$thumbnailFileName"

      thumbnail <- downloadService
        .download(thumbnailUri, filePath)
        .use { downloadResult =>
          downloadResult.data.compile.drain
            .productR(hashingService.hash(thumbnailUri.renderString))
            .map { fileKey =>
              FileResource(
                fileKey,
                timestamp,
                downloadResult.downloadedFileKey,
                downloadResult.mediaType,
                downloadResult.size
              )
            }
        }

      videoMetadata = VideoMetadata(uri, videoKey, videoSite, title, duration, size, thumbnail)

      scheduledVideoDownload = ScheduledVideoDownload(timestamp, timestamp, None, videoMetadata, 0, None)

      _ <- transaction {
        fileResourceDao
          .insert(thumbnail)
          .productR(videoMetadataDao.insert(videoMetadata))
          .productR(schedulingDao.insert(scheduledVideoDownload))
      }
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[ScheduledVideoDownload]] =
    transaction {
      schedulingDao.search(term, pageNumber, pageSize, sortBy, order)
    }

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[Int] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <- transaction {
        schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp)
      }

      _ <- if (result == 0) ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"ID not found: $id"))
      else Applicative[F].unit
    } yield result

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT
      .liftF(JodaClock[F].timestamp)
      .flatMapF { timestamp =>
        transaction {
          OptionT(schedulingDao.retrieveStaledTask(timestamp))
            .orElseF(schedulingDao.retrieveNewTask(timestamp))
            .value
        }
      }

  override val active: Stream[F, ScheduledVideoDownload] =
    Stream
      .awakeDelay[F](Duration.create(500, TimeUnit.MILLISECONDS))
      .productR {
        Stream.eval(JodaClock[F].timestamp)
      }
      .scan[(Option[DateTime], Option[DateTime])]((None, None)) {
        case ((_, Some(previous)), timestamp) => (Some(previous), Some(timestamp))
        case (_, timestamp) => (None, Some(timestamp))
      }
      .collect {
        case (Some(start), Some(end)) => (start, end)
      }
      .evalMap {
        case (start, end) => transaction(schedulingDao.active(start, end))
      }
      .flatMap { scheduledDownloads =>
        Stream.emits(scheduledDownloads)
      }
}
