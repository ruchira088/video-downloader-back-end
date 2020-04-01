package com.ruchij.services.scheduler

import cats.Applicative
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.ruchij.config.BatchConfiguration
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.worker.{Worker, WorkerFactory}

class SchedulerImpl[F[_]: Concurrent: Timer, A <: Worker[F]](
  workerFactory: WorkerFactory[F, A],
  schedulingService: SchedulingService[F],
  batchConfiguration: BatchConfiguration
) extends Scheduler[F] {

  override type Result = Nothing

  override val run: F[Nothing] =
    Semaphore[F](batchConfiguration.workerCount).flatMap[Nothing](runScheduler)

  def runScheduler(semaphore: Semaphore[F]): F[Nothing] =
    semaphore.acquire
      .product {
        Concurrent[F].start {
          schedulingService.acquireTask
            .semiflatMap { task =>
              workerFactory.newWorker.flatMap(_.execute(task)).productR(Applicative[F].unit)
            }
            .getOrElseF {
              Timer[F].sleep(batchConfiguration.idleTimeout)
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
