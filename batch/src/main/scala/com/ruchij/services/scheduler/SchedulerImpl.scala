package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.config.WorkerConfiguration
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.workers.WorkerDao
import com.ruchij.daos.workers.models.Worker
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.logging.Logger
import com.ruchij.services.scheduler.SchedulerImpl.DELAY
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.sync.SynchronizationService
import com.ruchij.services.sync.models.SynchronizationResult
import com.ruchij.services.worker.WorkExecutor
import org.joda.time.{DateTime, LocalTime}

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
      .delay(Random.nextLong(DELAY.toMillis))
      .flatMap { sleepDuration =>
        Timer[F].sleep(DELAY + FiniteDuration(sleepDuration, TimeUnit.MILLISECONDS))
      }
      .productR(Clock[F].realTime(TimeUnit.MILLISECONDS))
      .flatMap { timestamp =>
        OptionT {
          transaction {
            OptionT(workerDao.idleWorker).flatMap { worker =>
              OptionT(workerDao.reserveWorker(worker.id, new DateTime(timestamp)))
            }.value
          }
        }.getOrElseF(idleWorker)
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
                      .product(OptionT.liftF(Clock[F].realTime(TimeUnit.MILLISECONDS)))
                      .flatMapF {
                        case (task, timestamp) =>
                          transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, new DateTime(timestamp)))
                            .as[Option[ScheduledVideoDownload]](Some(task))
                      }
                      .semiflatMap(workExecutor.execute)
                      .productR(OptionT.liftF(Applicative[F].unit))
                      .getOrElseF(Applicative[F].unit)
                  } {
                    Clock[F]
                      .realTime(TimeUnit.MILLISECONDS)
                      .flatMap { timestamp =>
                        OptionT(transaction(workerDao.release(worker.id, new DateTime(timestamp))))
                          .getOrElseF {
                            ApplicativeError[F, Throwable].raiseError(
                              ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
                            )
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
      .productR[Nothing] {
        Sync[F].defer[Nothing](run)
      }

  val syncWorkers: F[Int] =
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

  override val init: F[SynchronizationResult] =
    logger
      .infoF("Batch initialization started")
      .productR(syncWorkers)
      .flatMap(count => logger.infoF(s"New workers created: $count"))
      .productR(synchronizationService.sync)
      .flatTap(result => logger.infoF(result.prettyPrint))
      .productL(logger.infoF("Batch initialization completed"))

}

object SchedulerImpl {
  val DELAY: FiniteDuration = 10 seconds

  def isWorkPeriod[F[_]: Clock: Monad](start: LocalTime, end: LocalTime): F[Boolean] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .map { timestamp =>
        val localTime = new DateTime(timestamp).toLocalTime

        if (start.isBefore(end))
          localTime.isAfter(start) && localTime.isBefore(end)
        else
          localTime.isAfter(start) || localTime.isBefore(end)
      }
}
