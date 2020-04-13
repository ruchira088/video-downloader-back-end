package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.ruchij.config.WorkerConfiguration
import com.ruchij.services.scheduler.SchedulerImpl.MAX_DELAY
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.worker.WorkExecutor

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class SchedulerImpl[F[_]: Concurrent: Timer](
  schedulingService: SchedulingService[F],
  workExecutor: WorkExecutor[F],
  workerConfiguration: WorkerConfiguration
) extends Scheduler[F] {

  override type Result = Nothing

  override val run: F[Nothing] =
    Semaphore[F](workerConfiguration.maxConcurrentDownloads).flatMap[Nothing](runScheduler)

  def runScheduler(semaphore: Semaphore[F]): F[Nothing] =
    semaphore.acquire
      .product {
        Sync[F].delay(Random.nextLong(MAX_DELAY.toMillis))
          .flatMap { long => Timer[F].sleep(FiniteDuration(long, TimeUnit.MILLISECONDS)) }
      }
      .product {
        Concurrent[F].start {
          schedulingService.acquireTask
            .semiflatMap { task =>
              workExecutor.execute(task).productR(Applicative[F].unit)
            }
            .getOrElseF(Applicative[F].unit)
            .productL(semaphore.release)
            .recoverWith {
              case _ => semaphore.release
            }

        }
      }
      .productR[Nothing] {
        Sync[F].defer[Nothing](runScheduler(semaphore))
      }
}

object SchedulerImpl {
  val MAX_DELAY: FiniteDuration = 20 seconds
}
