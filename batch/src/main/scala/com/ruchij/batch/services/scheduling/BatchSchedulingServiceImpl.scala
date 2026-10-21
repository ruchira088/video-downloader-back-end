package com.ruchij.batch.services.scheduling

import cats.data.OptionT
import cats.effect.Async
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.batch.daos.scheduling.BatchSchedulingDao
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.types.{Clock, RandomGenerator}
import fs2.Stream

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

class BatchSchedulingServiceImpl[F[_]: Async: Clock, T[_]: MonadThrow](
  downloadProgressPublisher: Publisher[F, DownloadProgress],
  workerStatusSubscriber: Subscriber[F, WorkerStatusUpdate],
  scheduledVideoDownloadPubSub: PubSub[F, ScheduledVideoDownload],
  repositoryService: RepositoryService[F],
  schedulingDao: SchedulingDao[T],
  batchSchedulingDao: BatchSchedulingDao[T],
  workerDao: WorkerDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  storageConfiguration: StorageConfiguration,
  retryPolicy: RetryPolicy,
  jitterGenerator: RandomGenerator[F, Double]
)(implicit transaction: T ~> F)
    extends BatchSchedulingService[F] {

  private val logger = Logger[BatchSchedulingServiceImpl[F, T]]

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT.liftF(Clock[F].timestamp).flatMapF { timestamp =>
      transaction(schedulingDao.acquireTask(timestamp))
    }

  override val staleTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      Clock[F].timestamp.flatMap(timestamp => transaction(schedulingDao.staleTask(20 seconds, timestamp)))
    }

  override def updateSchedulingStatusById(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    Clock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.updateSchedulingStatusById(id, status, timestamp)))
          .getOrElseF { ApplicativeError[F, Throwable].raiseError(notFound(id)) }
      }
      .flatTap(scheduledVideoDownloadPubSub.publishOne)

  override def transitionSchedulingStatusById(
    id: String,
    from: Set[SchedulingStatus],
    to: SchedulingStatus
  ): F[Option[ScheduledVideoDownload]] =
    Clock[F].timestamp
      .flatMap { timestamp =>
        transaction {
          OptionT(batchSchedulingDao.lockStatusById(id))
            .filter(from.contains)
            .flatMapF(_ => schedulingDao.updateSchedulingStatusById(id, to, timestamp))
            .value
        }
      }
      .flatTap(_.traverse_(scheduledVideoDownloadPubSub.publishOne))

  override def recordFailure(id: String, throwable: Throwable): F[Option[ScheduledVideoDownload]] =
    for {
      timestamp <- Clock[F].timestamp
      jitter <- jitterGenerator.generate
      maybeUpdated <- transaction {
        // The row stays locked until the end of the transaction, so the status cannot change before it is written
        OptionT(batchSchedulingDao.lockStatusById(id))
          // Only the worker running a download may record its failure. If the user paused it, the workers were
          // paused, or it was completed or deleted in the meantime, a late failure must neither put it back in the
          // queue nor overwrite its status with Error.
          .filter(OwnedStatuses.contains)
          .flatMapF { _ =>
            OptionT(schedulingDao.getById(id, None)).flatMapF { current =>
              retryPolicy.nextDelay(current.retryCount, jitter) match {
                case Some(delay) =>
                  schedulingDao.scheduleRetry(id, throwable, timestamp.plusMillis(delay.toMillis), timestamp)

                case None => schedulingDao.setErrorById(id, throwable, timestamp)
              }
            }.value
          }
          .value
      }
      _ <- maybeUpdated.fold(
        logger.warn[F](s"Not recording the failure of id=$id, as its worker no longer owns it. error=$throwable")
      ) { updated =>
        logFailure(updated, throwable).productR(scheduledVideoDownloadPubSub.publishOne(updated))
      }
    } yield maybeUpdated

  // Downloaded is set before the file is probed and the video is saved, which can fail transiently too
  private val OwnedStatuses: Set[SchedulingStatus] =
    Set(SchedulingStatus.Acquired, SchedulingStatus.Active, SchedulingStatus.Downloaded)

  private def logFailure(scheduledVideoDownload: ScheduledVideoDownload, throwable: Throwable): F[Unit] =
    if (scheduledVideoDownload.status == SchedulingStatus.Queued)
      logger.warn[F] {
        s"Download failed, retrying. id=${scheduledVideoDownload.videoMetadata.id}, " +
          s"attempt=${scheduledVideoDownload.retryCount}/${retryPolicy.configuration.maxAttempts}, " +
          s"retryAfter=${scheduledVideoDownload.retryAfter.getOrElse("-")}, error=${throwable.getMessage}"
      }
    else Applicative[F].unit

  override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): F[Seq[ScheduledVideoDownload]] =
    for {
      updated <- transaction(schedulingDao.updateSchedulingStatus(from, to))
      _ <- scheduledVideoDownloadPubSub.publish(Stream.emits(updated)).compile.drain
    } yield updated

  override def completeScheduledVideoDownload(id: String): F[ScheduledVideoDownload] =
    Clock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.markScheduledVideoDownloadAsComplete(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      }
      .flatTap(scheduledVideoDownloadPubSub.publishOne)

  override def updateTimedOutTasks(timeout: FiniteDuration): F[Seq[ScheduledVideoDownload]] =
    Clock[F].timestamp
      .flatMap { timestamp =>
        transaction(schedulingDao.updateTimedOutTasks(timeout, timestamp))
      }
      .flatTap { staleScheduledVideoDownloads =>
        scheduledVideoDownloadPubSub.publish(Stream.emits(staleScheduledVideoDownloads)).compile.drain
      }

  override def publishDownloadProgress(id: String, downloadedBytes: Long): F[Unit] =
    for {
      timestamp <- Clock[F].timestamp
      result <- downloadProgressPublisher.publishOne(DownloadProgress(id, timestamp, downloadedBytes))
    } yield result

  override def subscribeToWorkerStatusUpdates(groupId: String): Stream[F, WorkerStatusUpdate] =
    workerStatusSubscriber
      .subscribe(groupId)
      .evalMap { committableRecord =>
        commitOrLog("WorkerStatusSubscriber", groupId) {
          workerStatusSubscriber.commit(List(committableRecord))
        }.as(workerStatusSubscriber.extractValue(committableRecord))
      }

  override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[F, ScheduledVideoDownload] =
    scheduledVideoDownloadPubSub
      .subscribe(groupId)
      .evalMap { committableRecord =>
        commitOrLog("ScheduledVideoDownloadPubSub", groupId) {
          scheduledVideoDownloadPubSub.commit(List(committableRecord))
        }.as(scheduledVideoDownloadPubSub.extractValue(committableRecord))
      }

  /**
    * A failed commit, such as one interrupted by a consumer group rebalance, is logged rather than failing the
    * subscription. Committing a later message covers this one too, while restarting the subscription would leave a
    * gap in which the messages for this instance could be missed.
    */
  private def commitOrLog(subscriber: String, groupId: String)(commit: F[Unit]): F[Unit] =
    commit
      .productR(logger.debug[F](s"$subscriber(groupId=$groupId) committed 1 message"))
      .handleErrorWith { throwable =>
        logger.warn[F](s"$subscriber(groupId=$groupId) was unable to commit a message. error=$throwable")
      }

  override def publishScheduledVideoDownload(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id, None)))
      .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      .flatTap(scheduledVideoDownloadPubSub.publishOne)

  override def deleteById(id: String): F[ScheduledVideoDownload] =
    logger
      .info[F](s"Deleting ScheduledVideoDownload with id=$id")
      .productR {
        OptionT {
          transaction {
            OptionT(schedulingDao.getById(id, None)).productL {
              OptionT.liftF {
                workerDao
                  .clearScheduledVideoDownload(id)
                  .productR(schedulingDao.deleteById(id))
                  .productR(videoMetadataDao.deleteById(id))
              }
            }.value
          }
        }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
          .flatTap { scheduledVideoDownload =>
            if (scheduledVideoDownload.downloadedBytes > 0) {
              logger.info[F](s"Deleting video file for ScheduledVideoDownload with id=$id")
                .productR {
                  repositoryService
                    .list(storageConfiguration.videoFolder)
                    .find { path =>
                      path.split("/").toList.lastOption.exists(_.startsWith(id))
                    }
                    .evalMap(repositoryService.delete)
                    .compile
                    .drain
                }

            } else Applicative[F].unit
          }
      }

}
