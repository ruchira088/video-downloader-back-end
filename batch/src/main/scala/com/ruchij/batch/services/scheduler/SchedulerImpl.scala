package com.ruchij.batch.services.scheduler

import cats.data.OptionT
import cats.effect.kernel.Outcome.{Canceled, Errored}
import cats.effect.{Async, MonadCancelThrow}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.services.scheduler.SchedulerImpl.WorkerPollPeriod
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.{CommittableRecord, HttpMetric}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.types.JodaClock
import com.ruchij.core.utils.Http4sUtils.ChunkSize
import fs2.Stream
import fs2.concurrent.Topic
import org.joda.time.LocalTime

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

class SchedulerImpl[F[_]: Async: JodaClock, T[_]: Monad, M[_]](
  batchSchedulingService: BatchSchedulingService[F],
  synchronizationService: SynchronizationService[F],
  batchVideoService: BatchVideoService[F],
  workExecutor: WorkExecutor[F],
  httpMetricSubscriber: Subscriber[F, CommittableRecord[M, *], HttpMetric],
  workerDao: WorkerDao[T],
  workerConfiguration: WorkerConfiguration,
  instanceId: String
)(implicit transaction: T ~> F)
    extends Scheduler[F] {

  override type InitializationResult = SynchronizationResult

  private val logger = Logger[SchedulerImpl[F, T, M]]

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
                  .error[F]("Error occurred when fetching idle worker", throwable)
                  .productR(Applicative[F].pure(None))
            }
          }
        }
      }
      .collect { case Some(worker) => worker }

  def performWork(
    worker: Worker,
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload],
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): F[Option[Video]] =
    MonadCancelThrow[F].bracketCase {
      batchSchedulingService.staleTask.orElse(batchSchedulingService.acquireTask).value
    } { taskOpt =>
      OptionT
        .fromOption[F](taskOpt)
        .product(OptionT.liftF(JodaClock[F].timestamp))
        .flatMapF {
          case (task, timestamp) =>
            batchSchedulingService
              .updateSchedulingStatusById(task.videoMetadata.id, SchedulingStatus.Active)
              .product(transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, timestamp)))
              .as(Option(timestamp -> task))
        }
        .flatMapF {
          case (timestamp, scheduledVideoDownload) =>
            ApplicativeError[F, Throwable].recoverWith {
              workExecutor
                .execute(
                  scheduledVideoDownload,
                  worker,
                  scheduledVideoDownloadUpdates
                    .map { value: ScheduledVideoDownload =>
                      value.videoMetadata.id == scheduledVideoDownload.videoMetadata.id &&
                      List(SchedulingStatus.Paused, SchedulingStatus.Deleted).contains(value.status) &&
                      value.lastUpdatedAt.isAfter(timestamp)
                    }
                    .merge {
                      workerStatusUpdates.map { workerStatusUpdate: WorkerStatusUpdate =>
                        workerStatusUpdate.workerStatus == WorkerStatus.Paused
                      }
                    }
                    .filter(identity)
                    .productR[Boolean](Stream.raiseError[F](PausedVideoDownload))
                )
                .map[Option[Video]](Some.apply)
            } {
              case PausedVideoDownload =>
                logger.info[F](s"${scheduledVideoDownload.videoMetadata.url} has been paused").as(None)
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
      case (Some(scheduledVideoDownload), Errored(_)) =>
        batchSchedulingService
          .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Error)
          .productR(Applicative[F].unit)

      case (Some(scheduledVideoDownload), Canceled()) =>
        batchSchedulingService
          .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Queued)
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
    Stream
      .eval(Topic[F, ScheduledVideoDownload])
      .product(Stream.eval(Topic[F, WorkerStatusUpdate]))
      .flatMap {
        case (scheduledVideoDownloadsTopic, workerStatusUpdatesTopic) =>
          val workerStatusUpdates: Stream[F, WorkerStatusUpdate] =
            workerStatusUpdatesTopic.subscribe(Int.MaxValue)

          idleWorkers
            .concurrently(publishScheduledVideoDownloadsUpdatesToTopic(scheduledVideoDownloadsTopic))
            .concurrently(publishWorkerStatusUpdatesToTopic(workerStatusUpdatesTopic))
            .concurrently(cleanUpStaleScheduledVideoDownloads)
            .concurrently(updateVideoWatchTimes(ChunkSize))
            .concurrently(cleanUpStaleWorkers)
            .concurrently(updateWorkersAndScheduledVideoDownloads(workerStatusUpdates))
            .parEvalMapUnordered(workerConfiguration.maxConcurrentDownloads) { worker =>
              performWorkDuringWorkPeriod(worker, scheduledVideoDownloadsTopic.subscribe(Int.MaxValue), workerStatusUpdates)
            }
            .collect {
              case Some(video) => video
            }
      }

  def updateWorkersAndScheduledVideoDownloads(
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): Stream[F, Seq[Worker]] =
    workerStatusUpdates.evalMap { workerStatusUpdate =>
      val updateScheduledVideoDownloads: F[Seq[ScheduledVideoDownload]] =
        workerStatusUpdate.workerStatus match {
          case WorkerStatus.Paused =>
            batchSchedulingService.updateSchedulingStatus(SchedulingStatus.Active, SchedulingStatus.WorkersPaused)

          case WorkerStatus.Available =>
            batchSchedulingService.updateSchedulingStatus(SchedulingStatus.WorkersPaused, SchedulingStatus.Queued)

          case _ => Applicative[F].pure(Seq.empty[ScheduledVideoDownload])
        }

      updateScheduledVideoDownloads.productR {
        transaction(workerDao.updateWorkerStatuses(workerStatusUpdate.workerStatus))
      }
    }

  def performWorkDuringWorkPeriod(
    worker: Worker,
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload],
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): F[Option[Video]] =
    MonadCancelThrow[F].guarantee(
      SchedulerImpl
        .isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
        .flatMap { isWorkPeriod =>
          if (isWorkPeriod)
            performWork(worker, scheduledVideoDownloadUpdates, workerStatusUpdates)
              .recoverWith {
                case throwable =>
                  logger.error[F]("Error occurred in work scheduler", throwable).as(None)
              } else OptionT.none[F, Video].value
        },
      releaseWorker(worker)
    )

  def updateVideoWatchTimes(minimumChunkSize: Long): Stream[F, Unit] =
    httpMetricSubscriber
      .subscribe("batch-scheduler")
      .evalTap {
        case CommittableRecord(httpMetric, _) =>
          httpMetric.contentType
            .product(httpMetric.size)
            .flatMap {
              case (contentType, size) =>
                if (contentType.isVideo && size >= minimumChunkSize)
                  httpMetric.uri.path.segments.lastOption.map(lastSegment => (lastSegment.encoded, size))
                else None
            }
            .fold(Applicative[F].unit) {
              case (resourceId, size) =>
                ApplicativeError[F, Throwable].handleErrorWith {
                  batchVideoService
                    .fetchByVideoFileResourceId(resourceId)
                    .flatMap { video =>
                      val watchDuration =
                        FiniteDuration(
                          math.round((size.toDouble / video.fileResource.size) * video.videoMetadata.duration.toMillis),
                          TimeUnit.MILLISECONDS
                        )

                      batchVideoService
                        .incrementWatchTime(video.videoMetadata.id, watchDuration)
                        .productR(Applicative[F].unit)
                    }
                } { throwable =>
                  logger
                    .error[F](s"Unable to update watch time for ${httpMetric.uri}", throwable)
                }
            }
      }
      .groupWithin(20, 5 seconds)
      .evalMap { chunk =>
        httpMetricSubscriber
          .commit(chunk)
          .productR {
            logger.trace[F] {
              s"HttpMetricSubscriber(groupId=batch-scheduler) committed ${chunk.size} messages"
            }
          }
      }

  def publishScheduledVideoDownloadsUpdatesToTopic(topic: Topic[F, ScheduledVideoDownload]): Stream[F, Unit] =
    topic.publish {
      batchSchedulingService.subscribeToScheduledVideoDownloadUpdates(s"batch-scheduler-$instanceId")
    }

  def publishWorkerStatusUpdatesToTopic(topic: Topic[F, WorkerStatusUpdate]): Stream[F, Unit] =
    topic.publish {
      batchSchedulingService.subscribeToWorkerStatusUpdates(s"batch-scheduler-$instanceId")
    }

  val cleanUpStaleScheduledVideoDownloads: Stream[F, ScheduledVideoDownload] =
    Stream
      .eval(batchSchedulingService.updateTimedOutTasks(2 minutes))
      .repeat
      .metered(30 seconds)
      .flatMap(Stream.emits)

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
            workerDao.insert(Worker(Worker.workerIdFromIndex(index), WorkerStatus.Available, None, None, None))
          }.handleError(_ => 0)
        }
      }
      .map(_.sum)

  val cleanUpStaleWorkers: Stream[F, Int] =
    Stream
      .eval(logger.info[F]("cleanUpStaleWorkersTask started"))
      .productR {
        Stream
          .eval(JodaClock[F].timestamp)
          .repeat
          .metered(30 seconds)
          .evalMap { timestamp =>
            transaction {
              workerDao.cleanUpStaleWorkers(timestamp.minusMinutes(2))
            }
          }
      }

  override val init: F[SynchronizationResult] =
    logger
      .info[F]("Batch initialization started")
      .productR(newWorkers)
      .flatMap(count => logger.info[F](s"New workers created: $count"))
      .productR(synchronizationService.sync)
      .flatTap(result => logger.info[F](result.prettyPrint))
      .productL(logger.info[F]("Batch initialization completed"))
}

object SchedulerImpl {
  val WorkerPollPeriod: FiniteDuration = 1 second

  def isWorkPeriod[F[_]: JodaClock: Monad](start: LocalTime, end: LocalTime): F[Boolean] =
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
