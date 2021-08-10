package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.{ResourceConflictException, ResourceNotFoundException}
import com.ruchij.core.messaging.PubSub
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.SchedulingServiceImpl.notFound
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.models.DurationRange
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

class SchedulingServiceImpl[F[+ _]: Sync: Timer, T[_]: Monad](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[T],
  downloadProgressPubSub: PubSub[F, CommittableRecord[F, *], DownloadProgress],
  scheduledVideoDownloadPubSub: PubSub[F, CommittableRecord[F, *], ScheduledVideoDownload],
  workerStatusUpdatePubSub: PubSub[F, CommittableRecord[F, *], WorkerStatusUpdate]
)(implicit transaction: T ~> F)
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      searchResult <- transaction {
        schedulingDao.search(None, Some(NonEmptyList.of(uri)), DurationRange.All, 0, 1, SortBy.Date, Order.Descending, None)
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
      _ <- scheduledVideoDownloadPubSub.publishOne(scheduledVideoDownload)
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
      }
    else
      transaction(schedulingDao.search(term, videoUrls, durationRange, pageNumber, pageSize, sortBy, order, schedulingStatuses))

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(notFound(id))
      }

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      }
      .flatTap(value => scheduledVideoDownloadPubSub.publishOne(value))

  override def updateStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
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

      _ <- scheduledVideoDownloadPubSub.publishOne(updatedScheduledVideoDownload)
    } yield updatedScheduledVideoDownload

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT.liftF(JodaClock[F].timestamp).flatMapF {
      timestamp => transaction(schedulingDao.acquireTask(timestamp))
    }

  override val staleTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      JodaClock[F].timestamp.flatMap(timestamp => transaction(schedulingDao.staleTask(timestamp)))
    }

  override def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]] =
    JodaClock[F].timestamp.flatMap {
      timestamp => transaction(schedulingDao.updateTimedOutTasks(timeout, timestamp))
    }

  override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload] =
    scheduledVideoDownloadPubSub.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }

  override def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit] = {
    for {
      timestamp <- JodaClock[F].timestamp
      result <- downloadProgressPubSub.publishOne(DownloadProgress(id, timestamp, downloadedBytes))
    } yield result
  }

  override def subscribeToDownloadProgress(groupId: String): Stream[F, DownloadProgress] =
    downloadProgressPubSub.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <-
        OptionT {
          transaction {
            schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp)
          }
        }
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield result

  override def updateWorkerStatuses(workerStatus: WorkerStatus): F[Unit] =
    workerStatusUpdatePubSub.publishOne(WorkerStatusUpdate(workerStatus))

  override def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate] =
    workerStatusUpdatePubSub.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }
}

object SchedulingServiceImpl {
  def notFound(id: String): ResourceNotFoundException =
    ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id")
}
