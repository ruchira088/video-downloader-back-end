package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad}
import com.ruchij.config.WorkerConfiguration
import com.ruchij.daos.workers.WorkerLockDao
import com.ruchij.daos.workers.models.WorkerLock
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.scheduler.SchedulerImpl.MAX_DELAY
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.worker.WorkExecutor
import org.joda.time.{DateTime, LocalTime}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Random

class SchedulerImpl[F[_]: Concurrent: Timer](
  schedulingService: SchedulingService[F],
  workExecutor: WorkExecutor[F],
  workerLockDao: WorkerLockDao[F],
  workerConfiguration: WorkerConfiguration
) extends Scheduler[F] {

  override type Result = Nothing

  val acquireLock: F[WorkerLock] =
    workerLockDao.vacantLock
      .product(OptionT.liftF(Clock[F].realTime(TimeUnit.MILLISECONDS)))
      .flatMap {
        case (workerLock, timestamp) => workerLockDao.acquireLock(workerLock.id, new DateTime(timestamp))
      }
      .getOrElseF {
        Sync[F].delay(Random.nextLong(MAX_DELAY.toMillis))
          .flatMap { sleepDuration => Timer[F].sleep(FiniteDuration(sleepDuration, TimeUnit.MILLISECONDS)) }
          .productR(acquireLock)
      }

  override val run: F[Nothing] =
    acquireLock
      .flatMap { workerLock =>
        SchedulerImpl.isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
          .flatMap { isWorkPeriod =>
            if (isWorkPeriod)
              Concurrent[F]
                .start {
                  Bracket[F, Throwable].guarantee {
                    schedulingService.acquireTask.value
                      .flatMap {
                        _.fold(Applicative[F].unit) { task =>
                          workExecutor.execute(task).productR(Applicative[F].unit)
                        }
                      }
                  }  {
                    workerLockDao.releaseLock(workerLock.id)
                      .getOrElseF {
                        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"Worker lock not found. ID = ${workerLock.id}"))
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
}

object SchedulerImpl {
  val MAX_DELAY: FiniteDuration = 20 seconds

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
