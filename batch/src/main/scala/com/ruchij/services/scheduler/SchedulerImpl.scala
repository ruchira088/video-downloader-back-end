package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad}
import com.ruchij.config.WorkerConfiguration
import com.ruchij.daos.workers.WorkerDao
import com.ruchij.daos.workers.models.Worker
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.scheduler.SchedulerImpl.DELAY
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.sync.SynchronizationService
import com.ruchij.services.sync.models.SyncResult
import com.ruchij.services.worker.WorkExecutor
import org.joda.time.{DateTime, LocalTime}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Random

class SchedulerImpl[F[_]: Concurrent: Timer](
  schedulingService: SchedulingService[F],
  synchronizationService: SynchronizationService[F],
  workExecutor: WorkExecutor[F],
  workerDao: WorkerDao[F],
  workerConfiguration: WorkerConfiguration
) extends Scheduler[F] {

  override type Result = Nothing

  override type InitializationResult = SyncResult
  val idleWorker: F[Worker] =
    Sync[F]
      .delay(Random.nextLong(DELAY.toMillis))
      .flatMap { sleepDuration =>
        Timer[F].sleep(DELAY + FiniteDuration(sleepDuration, TimeUnit.MILLISECONDS))
      }
      .productR {
        workerDao.idleWorker
          .flatMap { workerLock =>
            workerDao.reserveWorker(workerLock.id)
          }
          .getOrElseF(idleWorker)
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
                      .flatTap(task => workerDao.assignTask(worker.id, task.videoMetadata.id))
                      .semiflatMap(workExecutor.execute)
                      .productR(OptionT.liftF(Applicative[F].unit))
                      .getOrElseF(Applicative[F].unit)
                  } {
                    workerDao
                      .release(worker.id)
                      .getOrElseF {
                        ApplicativeError[F, Throwable].raiseError(
                          ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
                        )
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

  override val init: F[SyncResult] = synchronizationService.sync
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
