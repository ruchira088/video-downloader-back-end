package com.ruchij.batch.services.scheduling

import cats.data.OptionT
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.{ApplicativeError, MonadError, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import com.ruchij.core.types.JodaClock
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

class BatchSchedulingServiceImpl[F[_]: Concurrent: Timer, T[_]: MonadError[*[_], Throwable], M[_]](
  downloadProgressPublisher: Publisher[F, DownloadProgress],
  workerStatusSubscriber: Subscriber[F, CommittableRecord[M, *], WorkerStatusUpdate],
  scheduledVideoDownloadPubSub: PubSub[F, CommittableRecord[M, *], ScheduledVideoDownload],
  schedulingDao: SchedulingDao[T]
)(implicit transaction: T ~> F)
    extends BatchSchedulingService[F] {

  private val logger = Logger[BatchSchedulingServiceImpl[F, T, M]]

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT.liftF(JodaClock[F].timestamp).flatMapF { timestamp =>
      transaction(schedulingDao.acquireTask(timestamp))
    }

  override val staleTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      JodaClock[F].timestamp.flatMap(timestamp => transaction(schedulingDao.staleTask(timestamp)))
    }

  override def updateSchedulingStatusById(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .map { timestamp =>
        for {
          scheduledVideoDownload <- OptionT(schedulingDao.getById(id))
          _ <- OptionT.liftF(scheduledVideoDownload.status.validateTransition(status).toType[T, Throwable])
          updated <- OptionT(schedulingDao.updateSchedulingStatusById(id, status, timestamp))
        }
        yield updated
      }
      .flatMap { maybeUpdatedT =>
          OptionT(transaction(maybeUpdatedT.value))
            .getOrElseF { ApplicativeError[F, Throwable].raiseError(notFound(id)) }
      }
      .flatTap(scheduledVideoDownloadPubSub.publishOne)

  override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): F[Seq[ScheduledVideoDownload]] =
    for {
      _ <- from.validateTransition(to).toType[F, Throwable]
      updated <- transaction(schedulingDao.updateSchedulingStatus(from, to))
      _ <- scheduledVideoDownloadPubSub.publish(Stream.emits(updated)).compile.drain
    }
    yield updated

  override def completeScheduledVideoDownload(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.markScheduledVideoDownloadAsComplete(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      }
      .flatTap(scheduledVideoDownloadPubSub.publishOne)

  override def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]] =
    JodaClock[F].timestamp.flatMap { timestamp =>
      transaction(schedulingDao.updateTimedOutTasks(timeout, timestamp))
    }

  override def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit] = {
    for {
      timestamp <- JodaClock[F].timestamp
      result <- downloadProgressPublisher.publishOne(DownloadProgress(id, timestamp, downloadedBytes))
    } yield result
  }

  override def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate] =
    workerStatusSubscriber.subscribe(groupId)
      .evalMap {
        committableRecord =>
          workerStatusSubscriber.commit(List(committableRecord))
            .product {
              logger.debug(s"DownloadProgressSubscriber(groupId=$groupId) committed 1 message")
            }
            .as(committableRecord.value)
      }

  override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload] =
    scheduledVideoDownloadPubSub.subscribe(groupId)
      .evalMap {
        committableRecord =>
          scheduledVideoDownloadPubSub.commit(List(committableRecord))
            .product {
              logger.debug(s"ScheduledVideoDownloadPubSub(groupId=$groupId) committed 1 message")
            }
            .as(committableRecord.value)
      }

}