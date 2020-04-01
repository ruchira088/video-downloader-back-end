package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.ruchij.config.WorkerConfiguration
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.worker.WorkExecutor

import scala.concurrent.duration.FiniteDuration
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
        Sync[F].delay(Random.nextInt(1000))
          .flatMap { int => Timer[F].sleep(FiniteDuration(int, TimeUnit.MILLISECONDS)) }
      }
      .product {
        Concurrent[F].start {
          schedulingService.acquireTask
            .semiflatMap { task =>
              workExecutor.execute(task).productR(Applicative[F].unit)
            }
            .getOrElseF {
              Timer[F].sleep(workerConfiguration.idleTimeout)
            }
            .product {
              semaphore.release
            }
        }
      }
      .productR[Nothing] {
        Sync[F].defer[Nothing](runScheduler(semaphore))
      }
}
