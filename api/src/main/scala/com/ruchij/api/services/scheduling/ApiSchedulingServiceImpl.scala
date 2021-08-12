package com.ruchij.api.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Clock, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceConflictException
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.messaging.{Publisher, Subscriber}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.models.DurationRange
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri

class ApiSchedulingServiceImpl[F[_]: Sync: Clock, T[_]](
  videoAnalysisService: VideoAnalysisService[F],
  scheduledVideoDownloadPublisher: Publisher[F, ScheduledVideoDownload],
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[F, *], DownloadProgress],
  workerStatusPublisher: Publisher[F, WorkerStatusUpdate],
  schedulingDao: SchedulingDao[T]
)(implicit transaction: T ~> F)
    extends ApiSchedulingService[F] {
  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      searchResult <- transaction {
        schedulingDao.search(
          None,
          Some(NonEmptyList.of(uri)),
          DurationRange.All,
          0,
          1,
          SortBy.Date,
          Order.Descending,
          None
        )
      }

      _ <- if (searchResult.nonEmpty)
        ApplicativeError[F, Throwable].raiseError(ResourceConflictException(s"$uri has already been scheduled"))
      else Applicative[F].unit

      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        0,
        videoMetadataResult.value,
        None
      )

      _ <- transaction(schedulingDao.insert(scheduledVideoDownload))
      _ <- scheduledVideoDownloadPublisher.publishOne(scheduledVideoDownload)
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]]
  ): F[Seq[ScheduledVideoDownload]] =
    if (sortBy == SortBy.WatchTime)
      ApplicativeError[F, Throwable].raiseError {
        new IllegalArgumentException("Searching for scheduled videos by watch_time is not valid")
      } else
      transaction(
        schedulingDao.search(term, videoUrls, durationRange, pageNumber, pageSize, sortBy, order, schedulingStatuses)
      )

  override def subscribeToDownloadProgress(groupId: String): Stream[F, DownloadProgress] =
    downloadProgressSubscriber.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }

  override def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      scheduledVideoDownload <- OptionT(transaction(schedulingDao.getById(id)))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))

      _ <- if (scheduledVideoDownload.status.validTransitionStatuses.contains(status))
        Applicative[F].unit
      else
        ApplicativeError[F, Throwable].raiseError {
          new IllegalArgumentException(s"Transition not valid: ${scheduledVideoDownload.status} -> $status")
        }

      updatedScheduledVideoDownload <- OptionT(transaction(schedulingDao.updateStatus(id, status, timestamp)))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))

      _ <- scheduledVideoDownloadPublisher.publishOne(updatedScheduledVideoDownload)
    } yield updatedScheduledVideoDownload

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(notFound(id))
      }

  override def updateWorkerStatuses(workerStatus: WorkerStatus): F[Unit] =
    workerStatusPublisher.publishOne(WorkerStatusUpdate(workerStatus))

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <- OptionT {
        transaction {
          schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp)
        }
      }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield result
}
