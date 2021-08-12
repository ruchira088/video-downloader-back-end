package com.ruchij.batch.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.models.DurationRange
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

class BatchSchedulingServiceImpl[F[_]: Sync: Timer, T[_]: Monad](
  downloadProgressPublisher: Publisher[F, DownloadProgress],
  workerStatusSubscriber: Subscriber[F, CommittableRecord[F, *], WorkerStatusUpdate],
  scheduledVideoDownloadPubSub: PubSub[F, CommittableRecord[F, *], ScheduledVideoDownload],
  schedulingDao: SchedulingDao[T]
)(implicit transaction: T ~> F)
    extends BatchSchedulingService[F] {

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

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT.liftF(JodaClock[F].timestamp).flatMapF { timestamp =>
      transaction(schedulingDao.acquireTask(timestamp))
    }

  override val staleTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      JodaClock[F].timestamp.flatMap(timestamp => transaction(schedulingDao.staleTask(timestamp)))
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

      _ <- scheduledVideoDownloadPubSub.publishOne(updatedScheduledVideoDownload)
    } yield updatedScheduledVideoDownload

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      }
      .flatTap(value => scheduledVideoDownloadPubSub.publishOne(value))

  override def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]] =
    JodaClock[F].timestamp.flatMap { timestamp =>
      transaction(schedulingDao.updateTimedOutTasks(timeout, timestamp))
    }

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <- OptionT {
        transaction {
          schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp)
        }
      }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield result

  override def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit] = {
    for {
      timestamp <- JodaClock[F].timestamp
      result <- downloadProgressPublisher.publishOne(DownloadProgress(id, timestamp, downloadedBytes))
    } yield result
  }

  override def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate] =
    workerStatusSubscriber.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }

  override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload] =
    scheduledVideoDownloadPubSub.subscribe(groupId).evalMap {
      case CommittableRecord(value, commit) => commit.as(value)
    }

}