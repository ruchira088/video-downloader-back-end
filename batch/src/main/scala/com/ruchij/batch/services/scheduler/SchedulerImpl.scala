package com.ruchij.batch.services.scheduler

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.services.scheduler.SchedulerImpl.WorkerPollPeriod
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.WorkerDao
import com.ruchij.core.daos.workers.models.Worker
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.joda.time.LocalTime

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

class SchedulerImpl[F[_]: Concurrent: Timer, T[_]: Monad](
  schedulingService: SchedulingService[F],
  synchronizationService: SynchronizationService[F],
  workExecutor: WorkExecutor[F],
  workerDao: WorkerDao[T],
  workerConfiguration: WorkerConfiguration
)(implicit transaction: T ~> F)
    extends Scheduler[F] {

  override type InitializationResult = SynchronizationResult

  private val logger = Logger[F, SchedulerImpl[F, T]]

  val idleWorkers: Stream[F, Worker] =
    Stream
      .fixedRate[F](WorkerPollPeriod)
      .zipRight {
        Stream.repeatEval {
          JodaClock[F].timestamp.flatMap { timestamp =>
            transaction {
              OptionT(workerDao.idleWorker)
                .flatMap { worker =>
                  OptionT(workerDao.reserveWorker(worker.id, timestamp))
                }
                .value
            }
              .recoverWith {
                case throwable =>
                  logger
                    .errorF("Error occurred when fetching idle worker", throwable)
                    .productR(Applicative[F].pure(None))
              }
          }
        }
      }
      .collect { case Some(worker) => worker }

  def performWork(worker: Worker): F[Option[Video]] =
    Bracket[F, Throwable].guarantee {
      schedulingService.acquireTask
        .product(OptionT.liftF(JodaClock[F].timestamp))
        .flatMapF {
          case (task, timestamp) =>
            transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, timestamp))
              .as(Option(task))
        }
        .semiflatMap(scheduledVideoDownload => workExecutor.execute(scheduledVideoDownload, worker))
        .semiflatMap { video =>
          JodaClock[F].timestamp.flatMap { timestamp =>
            OptionT(transaction(workerDao.completeTask(worker.id, video.videoMetadata.id, timestamp)))
              .getOrElseF {
                ApplicativeError[F, Throwable].raiseError {
                  ResourceNotFoundException(
                    s"Unable to complete worker task workerId = ${worker.id}, taskId = ${video.videoMetadata.id}"
                  )
                }
              }
          }
            .as(video)
        }
        .value
    }(postWorkTask(worker))

  def postWorkTask(worker: Worker): F[Unit] =
    OptionT(transaction(workerDao.release(worker.id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
        }
      }
      .productR(Applicative[F].unit)

  override val run: Stream[F, Video] =
    idleWorkers.parEvalMapUnordered(workerConfiguration.maxConcurrentDownloads) { worker =>
      SchedulerImpl
        .isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
        .flatMap { isWorkPeriod =>
          if (isWorkPeriod)
            performWork(worker)
              .recoverWith {
                case throwable =>
                  logger.errorF("Error occurred in work scheduler", throwable).as(None)
              }
          else postWorkTask(worker).as[Option[Video]](None)
        }
    }
      .collect {
        case Some(video) => video
      }

  val newWorkers: F[Int] =
    Range(0, workerConfiguration.maxConcurrentDownloads).toList
      .traverse { index =>
        transaction(workerDao.getById(Worker.workerIdFromIndex(index))).map(index -> _)
      }
      .map {
        _.collect {
          case (index, None) => index
        }
      }
      .flatMap {
        _.traverse { index =>
          transaction {
            workerDao.insert(Worker(Worker.workerIdFromIndex(index), None, None))
          }
        }
      }
      .map(_.sum)

  val resetWorkers: F[Int] = transaction(workerDao.resetWorkers)

  override val init: F[SynchronizationResult] =
    logger
      .infoF("Batch initialization started")
      .productR(newWorkers)
      .flatMap(count => logger.infoF(s"New workers created: $count"))
      .productR(resetWorkers)
      .productR(logger.infoF("Workers have been reset"))
      .productR(synchronizationService.sync)
      .flatTap(result => logger.infoF(result.prettyPrint))
      .productL(logger.infoF("Batch initialization completed"))

}

object SchedulerImpl {
  val WorkerPollPeriod: FiniteDuration = 1 second

  def isWorkPeriod[F[_]: Clock: Monad](start: LocalTime, end: LocalTime): F[Boolean] =
    JodaClock[F].timestamp
      .map { timestamp =>
        val localTime = timestamp.toLocalTime

        if (start.isBefore(end))
          localTime.isAfter(start) && localTime.isBefore(end)
        else
          localTime.isAfter(start) || localTime.isBefore(end)
      }
}
