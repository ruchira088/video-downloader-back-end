package com.ruchij.batch.services.scheduler

import cats.data.OptionT
import cats.effect.{Bracket, Clock, Concurrent, ExitCase, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.services.scheduler.SchedulerImpl.WorkerPollPeriod
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.WorkerDao
import com.ruchij.core.daos.workers.models.Worker
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.types.JodaClock
import fs2.Stream
import fs2.concurrent.Topic
import org.joda.time.LocalTime

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

class SchedulerImpl[F[_]: Concurrent: Timer, T[_]: Monad](
  schedulingService: SchedulingService[F],
  synchronizationService: SynchronizationService[F],
  workExecutor: WorkExecutor[F],
  workerDao: WorkerDao[T],
  workerConfiguration: WorkerConfiguration,
  instanceId: String
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
              OptionT(workerDao.idleWorker).flatMap { worker =>
                OptionT(workerDao.reserveWorker(worker.id, timestamp))
              }.value
            }.recoverWith {
              case throwable =>
                logger
                  .errorF("Error occurred when fetching idle worker", throwable)
                  .productR(Applicative[F].pure(None))
            }
          }
        }
      }
      .collect { case Some(worker) => worker }

  def performWork(worker: Worker, topicUpdates: Topic[F, Option[ScheduledVideoDownload]]): F[Option[Video]] =
    Bracket[F, Throwable].bracketCase {
      OptionT(schedulingService.staleTasks.map(_.headOption)).orElse(schedulingService.acquireTask).value
    } { taskOpt =>
      OptionT
        .fromOption[F](taskOpt)
        .product(OptionT.liftF(JodaClock[F].timestamp))
        .flatMapF {
          case (task, timestamp) =>
            schedulingService.updateStatus(task.videoMetadata.id, SchedulingStatus.Active)
              .product(transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, timestamp)))
              .as(Option(timestamp -> task))
        }
        .flatMapF { case (timestamp, scheduledVideoDownload) =>
          ApplicativeError[F, Throwable].recoverWith {
            workExecutor
              .execute(
                scheduledVideoDownload,
                worker,
                topicUpdates
                  .subscribe(Int.MaxValue)
                  .collect { case Some(value) => value }
                  .filter { value: ScheduledVideoDownload =>
                    value.videoMetadata.id == scheduledVideoDownload.videoMetadata.id &&
                    List(SchedulingStatus.SchedulerPaused, SchedulingStatus.Paused).contains(value.status) &&
                      value.lastUpdatedAt.isAfter(timestamp)
                  }
                  .productR[Boolean](Stream.raiseError[F](PausedVideoDownload))
              )
              .map[Option[Video]](Some.apply)
          } {
            case PausedVideoDownload =>
              logger.infoF(s"${scheduledVideoDownload.videoMetadata.url} has been paused").as(None)
          }
        }
        .semiflatMap { video =>
          JodaClock[F].timestamp
            .flatMap { timestamp =>
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
    } {
      case (Some(scheduledVideoDownload), ExitCase.Error(_)) =>
        schedulingService
          .updateStatus(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Error)
          .productR(Applicative[F].unit)

      case (Some(scheduledVideoDownload), ExitCase.Canceled) =>
        schedulingService.updateStatus(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Queued)
          .productR(Applicative[F].unit)

      case (_, _) => Applicative[F].unit
    }

  def releaseWorker(worker: Worker): F[Unit] =
    OptionT(transaction(workerDao.releaseWorker(worker.id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
        }
      }
      .productR(Applicative[F].unit)

  override val run: Stream[F, Video] =
    Stream.eval(Topic[F, Option[ScheduledVideoDownload]](None))
      .flatMap { topic =>
        idleWorkers
          .concurrently(topic.publish(schedulingService.subscribeToUpdates(s"scheduler-$instanceId").map(Some.apply)))
          .parEvalMapUnordered(workerConfiguration.maxConcurrentDownloads) { worker =>
            Bracket[F, Throwable].guarantee {
              SchedulerImpl
                .isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
                .flatMap { isWorkPeriod =>
                  if (isWorkPeriod)
                    performWork(worker, topic)
                      .recoverWith {
                        case throwable =>
                          logger.errorF("Error occurred in work scheduler", throwable).as(None)
                      } else OptionT.none[F, Video].value
                }
            }(releaseWorker(worker))
          }
          .collect {
            case Some(video) => video
          }
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
            workerDao.insert(Worker(Worker.workerIdFromIndex(index), None, None, None, None))
          }
        }
      }
      .map(_.sum)

  val cleanUpStaleWorkersTask: Stream[F, Int] =
    Stream.eval(logger.infoF("cleanUpStaleWorkersTask started"))
      .productR {
        Stream.eval(JodaClock[F].timestamp)
          .repeat
          .metered(30 seconds)
          .evalMap { timestamp =>
            transaction {
              workerDao.cleanUpStaleWorkers(timestamp.minusMinutes(5))
            }
          }
      }

  override val init: F[SynchronizationResult] =
    logger
      .infoF("Batch initialization started")
      .productR(newWorkers)
      .flatMap(count => logger.infoF(s"New workers created: $count"))
      .productL {
        Concurrent[F].start(cleanUpStaleWorkersTask.compile.drain)
      }
      .productR(synchronizationService.sync)
      .flatTap(result => logger.infoF(result.prettyPrint))
      .productL(logger.infoF("Batch initialization completed"))
}

object SchedulerImpl {
  val WorkerPollPeriod: FiniteDuration = 1 second

  def isWorkPeriod[F[_]: Clock: Monad](start: LocalTime, end: LocalTime): F[Boolean] = {
    if (start == end)
      Applicative[F].pure(true)
    else
      JodaClock[F].timestamp
        .map { timestamp =>
          val localTime = timestamp.toLocalTime

          if (start.isBefore(end))
            localTime.isAfter(start) && localTime.isBefore(end)
          else
            localTime.isAfter(start) || localTime.isBefore(end)
        }
  }
}
