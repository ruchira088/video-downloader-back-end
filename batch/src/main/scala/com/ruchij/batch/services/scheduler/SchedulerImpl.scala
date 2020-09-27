package com.ruchij.batch.services.scheduler

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.services.scheduler.SchedulerImpl.Delay
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.workers.WorkerDao
import com.ruchij.core.daos.workers.models.Worker
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.types.JodaClock
import org.joda.time.LocalTime

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Random

class SchedulerImpl[F[_]: Concurrent: Timer, T[_]: Monad](
  schedulingService: SchedulingService[F],
  synchronizationService: SynchronizationService[F],
  workExecutor: WorkExecutor[F],
  workerDao: WorkerDao[T],
  workerConfiguration: WorkerConfiguration
)(implicit transaction: T ~> F)
    extends Scheduler[F] {

  override type Result = Nothing

  override type InitializationResult = SynchronizationResult

  private val logger = Logger[F, SchedulerImpl[F, T]]

  val idleWorker: F[Worker] =
    Sync[F]
      .delay(Random.nextLong(Delay.toMillis))
      .flatMap { sleepDuration =>
        Timer[F].sleep(Delay + FiniteDuration(sleepDuration, TimeUnit.MILLISECONDS))
      }
      .productR(JodaClock[F].timestamp)
      .flatMap { timestamp =>
        OptionT {
          transaction {
            OptionT(workerDao.idleWorker).flatMap { worker =>
              OptionT(workerDao.reserveWorker(worker.id, timestamp))
            }.value
          }
        }.getOrElseF(idleWorker)
      }
      .recoverWith {
        case throwable =>
          logger.errorF("Error occurred when fetching idle worker", throwable).productR(idleWorker)
      }

  override val run: F[Nothing] =
    idleWorker
      .flatMap { worker =>
        SchedulerImpl
          .isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
          .flatMap { isWorkPeriod =>
            if (isWorkPeriod)
              Concurrent[F]
                .start {
                  Bracket[F, Throwable].guarantee {
                    schedulingService.acquireTask
                      .product(OptionT.liftF(JodaClock[F].timestamp))
                      .flatMapF {
                        case (task, timestamp) =>
                          transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, timestamp))
                            .as[Option[ScheduledVideoDownload]](Some(task))
                      }
                      .semiflatMap(workExecutor.execute)
                      .productR(OptionT.liftF(Applicative[F].unit))
                      .getOrElseF(Applicative[F].unit)
                  } {
                    JodaClock[F].timestamp
                      .flatMap { timestamp =>
                        OptionT(transaction(workerDao.release(worker.id, timestamp)))
                          .getOrElseF {
                            ApplicativeError[F, Throwable].raiseError {
                              ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
                            }
                          }
                      }
                      .productR(Applicative[F].unit)
                  }
                }
                .productR(Applicative[F].unit)
            else
              Applicative[F].unit
          }
      }
      .recoverWith {
        case throwable =>
          logger.errorF("Error occurred in work scheduler", throwable)
      }
      .productR[Nothing] {
        Sync[F].defer[Nothing](run)
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
  val Delay: FiniteDuration = 10 seconds

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
