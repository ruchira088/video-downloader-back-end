package com.ruchij.batch.services.scheduler

import cats.data.OptionT
import cats.effect.kernel.Outcome.{Canceled, Errored}
import cats.effect.{Async, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.services.scheduler.SchedulerImpl.WorkerPollPeriod
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.{CommittableRecord, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoWatchHistoryService
import com.ruchij.core.types.JodaClock
import fs2.Stream
import fs2.concurrent.Topic
import org.joda.time.LocalTime

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

class SchedulerImpl[F[_]: Async: JodaClock, T[_]: MonadThrow, M[_]](
  batchSchedulingService: BatchSchedulingService[F],
  synchronizationService: SynchronizationService[F],
  batchVideoService: BatchVideoService[F],
  videoWatchHistoryService: VideoWatchHistoryService[F],
  workExecutor: WorkExecutor[F],
  videoWatchMetricsSubscriber: Subscriber[F, CommittableRecord[M, *], VideoWatchMetric],
  scanForVideosCommandSubscriber: Subscriber[F, CommittableRecord[M, *], ScanVideosCommand],
  workerDao: WorkerDao[T],
  workerConfiguration: WorkerConfiguration,
  instanceId: String
)(implicit transaction: T ~> F)
    extends Scheduler[F] {

  override type InitializationResult = Unit

  private val logger = Logger[SchedulerImpl[F, T, M]]

  private val idleWorkers: Stream[F, Worker] =
    Stream
      .fixedRate[F](WorkerPollPeriod)
      .zipRight {
        Stream.repeatEval {
          JodaClock[F].timestamp.flatMap { timestamp =>
            transaction {
              OptionT(workerDao.idleWorker).flatMap { worker =>
                OptionT(workerDao.reserveWorker(worker.id, workerConfiguration.owner, timestamp))
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
      .flatMap {
        case Some(worker) => Stream.eval(logger.info(s"Found idle worker workerId=${worker.id}")).as(worker)
        case _ => Stream.eval(logger.info("Idle workers not found")).productR(Stream.empty)
      }

  private def performWork(
    worker: Worker,
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload],
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): F[Option[Video]] =
    Sync[F].bracketCase {
      batchSchedulingService.staleTask.orElse(batchSchedulingService.acquireTask).value
    } { taskOpt =>
      OptionT
        .fromOption[F](taskOpt)
        .flatTapNone {
          logger.info[F](s"Task not found by workerId=${worker.id}")
        }
        .semiflatTap { scheduledVideoDownload =>
          logger.info[F](
            s"workerId=${worker.id} found task scheduledVideoDownloadId=${scheduledVideoDownload.videoMetadata.id}"
          )
        }
        .product(OptionT.liftF(JodaClock[F].timestamp))
        .flatMap {
          case (task, timestamp) =>
            OptionT(transaction(workerDao.assignTask(worker.id, task.videoMetadata.id, workerConfiguration.owner, timestamp)))
              .product { OptionT.liftF { batchSchedulingService.publishScheduledVideoDownload(task.videoMetadata.id) } }
              .as(timestamp -> task)
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
                    .productR[Boolean](Stream.raiseError[F](PausedVideoDownload)),
                  5
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
      case (Some(scheduledVideoDownload), Errored(exception)) =>
        batchSchedulingService
          .setErrorById(scheduledVideoDownload.videoMetadata.id, exception)
          .productR {
            logger.error(s"Error occurred in worker executor for scheduledVideoDownload ID=${scheduledVideoDownload.videoMetadata.id}", exception)
          }

      case (Some(scheduledVideoDownload), Canceled()) =>
        batchSchedulingService
          .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Queued)
          .productR(Applicative[F].unit)

      case (_, Errored(exception)) =>
        logger.error("Error occurred without scheduledVideoDownload", exception)

      case (_, _) => Applicative[F].unit
    }

  private def releaseWorker(worker: Worker): F[Unit] =
    OptionT(transaction(workerDao.releaseWorker(worker.id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Worker not found. ID = ${worker.id}")
        }
      }
      .productR(Applicative[F].unit)

  override val run: Stream[F, Video] =
    for {
      scheduledVideoDownloadsTopic <- Stream.eval(Topic[F, ScheduledVideoDownload])
      workerStatusUpdatesTopic <- Stream.eval(Topic[F, WorkerStatusUpdate])
      scanVideosCommandTopic <- Stream.eval(Topic[F, ScanVideosCommand])

      video <- run(scheduledVideoDownloadsTopic, workerStatusUpdatesTopic, scanVideosCommandTopic)
    } yield video

  private def run(
    scheduledVideoDownloadsTopic: Topic[F, ScheduledVideoDownload],
    workerStatusUpdatesTopic: Topic[F, WorkerStatusUpdate],
    scanVideosCommandTopic: Topic[F, ScanVideosCommand]
  ): Stream[F, Video] =
    idleWorkers
      .concurrently(publishScheduledVideoDownloadsUpdatesToTopic(scheduledVideoDownloadsTopic))
      .concurrently(publishWorkerStatusUpdatesToTopic(workerStatusUpdatesTopic))
      .concurrently(publishScanForVideosCommandsToTopic(scanVideosCommandTopic))
      .concurrently(cleanUpStaleScheduledVideoDownloads)
      .concurrently(updateVideoWatchTimes)
      .concurrently(cleanUpStaleWorkers)
      .concurrently(updateWorkersAndScheduledVideoDownloads(workerStatusUpdatesTopic.subscribe(Int.MaxValue)))
      .concurrently {
        scanVideosCommandTopic.subscribe(Int.MaxValue).evalMap { _ =>
          synchronizationService.sync
        }
      }
      .parEvalMapUnordered(workerConfiguration.maxConcurrentDownloads) { worker =>
        performWorkDuringWorkPeriod(
          worker,
          scheduledVideoDownloadsTopic.subscribe(Int.MaxValue),
          workerStatusUpdatesTopic.subscribe(Int.MaxValue)
        )
      }
      .collect {
        case Some(video) => video
      }

  private def updateWorkersAndScheduledVideoDownloads(
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

  private def performWorkDuringWorkPeriod(
    worker: Worker,
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload],
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): F[Option[Video]] =
    Sync[F]
      .guarantee(
        SchedulerImpl
          .isWorkPeriod[F](workerConfiguration.startTime, workerConfiguration.endTime)
          .flatMap { isWorkPeriod =>
            if (isWorkPeriod)
              performWork(worker, scheduledVideoDownloadUpdates, workerStatusUpdates)
            else OptionT.none[F, Video].value
          },
        releaseWorker(worker)
      )
      .recoverWith {
        case throwable =>
          logger.error[F]("Error occurred in work scheduler", throwable).as(None)
      }

  private val updateVideoWatchTimes: Stream[F, Unit] =
    videoWatchMetricsSubscriber
      .subscribe("batch-scheduler")
      .evalTap {
        case CommittableRecord(videoWatchMetric: VideoWatchMetric, _) =>
          batchVideoService
            .fetchByVideoFileResourceId(videoWatchMetric.videoFileResourceId)
            .flatMap { video =>
              val watchDuration =
                FiniteDuration(
                  math.round(
                    (videoWatchMetric.size.toDouble / video.fileResource.size) * video.videoMetadata.duration.toMillis
                  ),
                  TimeUnit.MILLISECONDS
                )

              batchVideoService
                .incrementWatchTime(video.videoMetadata.id, watchDuration)
                .productR {
                  videoWatchHistoryService
                    .addWatchHistory(
                      videoWatchMetric.userId,
                      video.videoMetadata.id,
                      videoWatchMetric.timestamp,
                      watchDuration
                    )
                }
            }
            .recoverWith {
              case exception =>
                logger.error("Error occurred updating video watch times", exception)
            }
      }
      .groupWithin(20, 5 seconds)
      .evalMap { chunk =>
        videoWatchMetricsSubscriber
          .commit(chunk)
          .productR {
            logger.trace[F] {
              s"VideoWatchMetricSubscriber(groupId=batch-scheduler) committed ${chunk.size} messages"
            }
          }
      }

  private def publishScheduledVideoDownloadsUpdatesToTopic(topic: Topic[F, ScheduledVideoDownload]): Stream[F, Unit] =
    topic.publish {
      batchSchedulingService.subscribeToScheduledVideoDownloadUpdates(s"batch-scheduler-$instanceId")
    }

  private def publishWorkerStatusUpdatesToTopic(topic: Topic[F, WorkerStatusUpdate]): Stream[F, Unit] =
    topic.publish {
      batchSchedulingService.subscribeToWorkerStatusUpdates(s"batch-scheduler-$instanceId")
    }

  private def publishScanForVideosCommandsToTopic(topic: Topic[F, ScanVideosCommand]): Stream[F, Unit] =
    topic.publish {
      scanForVideosCommandSubscriber
        .subscribe(s"batch-scheduler")
        .evalMap { record =>
          scanForVideosCommandSubscriber.commit(List(record)).as(record.value)
        }
    }

  private val cleanUpStaleScheduledVideoDownloads: Stream[F, ScheduledVideoDownload] =
    Stream
      .eval(batchSchedulingService.updateTimedOutTasks(2 minutes))
      .repeat
      .metered(30 seconds)
      .flatMap(Stream.emits)

  private val initWorkers: F[Int] =
    transaction {
      workerDao.all.flatMap { workers =>
        val validWorkerIds =
          Range(0, workerConfiguration.maxConcurrentDownloads).map(Worker.workerIdFromIndex)

        val workersToDelete =
          workers.filter { worker =>
            !validWorkerIds.contains(worker.id) && worker.status != WorkerStatus.Deleted
          }

        val workersToCreate =
          validWorkerIds.filter(workerId => !workers.exists(_.id == workerId)).toList

        workersToDelete
          .traverse(worker => workerDao.setStatus(worker.id, WorkerStatus.Deleted))
          .product {
            workersToCreate.traverse { workerId =>
              workerDao
                .insert(Worker(workerId, WorkerStatus.Available, None, None, None, None))
                .handleError(_ => 0)
            }
          }
          .map {
            case (deletions, insertions) => deletions.sum + insertions.sum
          }
      }
    }

  private val cleanUpStaleWorkers: Stream[F, Int] =
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

  override val init: F[Unit] =
    logger
      .info[F]("Batch initialization started")
      .productR(initWorkers)
      .flatMap(count => logger.info[F](s"New workers created: $count"))
//      .productR(synchronizationService.sync)
      .productL(logger.info[F]("Batch initialization completed"))
}

object SchedulerImpl {
  val WorkerPollPeriod: FiniteDuration = 1 second

  private def isWorkPeriod[F[_]: JodaClock: Applicative](start: LocalTime, end: LocalTime): F[Boolean] =
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
