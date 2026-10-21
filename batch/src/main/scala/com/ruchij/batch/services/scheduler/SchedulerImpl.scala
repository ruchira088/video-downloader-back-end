package com.ruchij.batch.services.scheduler

import cats.data.OptionT
import cats.effect.kernel.Outcome.{Canceled, Errored}
import cats.effect.{Async, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.detection.BatchDuplicateDetectionService
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.services.scheduler.SchedulerImpl.{
  StreamRestartDelay,
  VideoWatchMetricMaxRetries,
  VideoWatchMetricRetryBaseDelay,
  WorkerPollPeriod
}
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.VideoWatchMetric
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoWatchHistoryService
import com.ruchij.core.types.Clock
import fs2.Stream
import fs2.concurrent.Topic

import java.time.temporal.ChronoUnit
import java.time.{Duration, LocalTime, ZoneId}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

class SchedulerImpl[F[_]: Async: Clock, T[_]: MonadThrow](
  batchSchedulingService: BatchSchedulingService[F],
  synchronizationService: SynchronizationService[F],
  batchVideoService: BatchVideoService[F],
  videoWatchHistoryService: VideoWatchHistoryService[F],
  workExecutor: WorkExecutor[F],
  duplicateDetectionService: BatchDuplicateDetectionService[F],
  videoWatchMetricsSubscriber: Subscriber[F, VideoWatchMetric],
  scanForVideosCommandSubscriber: Subscriber[F, ScanVideosCommand],
  workerDao: WorkerDao[T],
  messageDao: MessageDao[T],
  maybeMessageTransaction: Option[T ~> F],
  workerConfiguration: WorkerConfiguration,
  instanceId: String
)(implicit transaction: T ~> F)
    extends Scheduler[F] {

  override type InitializationResult = Unit

  private val logger = Logger[SchedulerImpl[F, T]]

  private val idleWorkers: Stream[F, Worker] =
    Stream
      .fixedRate[F](WorkerPollPeriod)
      .zipRight {
        Stream.repeatEval {
          Clock[F].timestamp.flatMap { timestamp =>
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
        case Some(worker) => Stream.eval(logger.info[F](s"Found idle worker workerId=${worker.id}")).as(worker)
        case _ => Stream.eval(logger.info[F]("Idle workers not found")).productR(Stream.empty)
      }

  private def performWork(
    worker: Worker,
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload],
    workerStatusUpdates: Stream[F, WorkerStatusUpdate]
  ): F[Option[Video]] =
    Sync[F].bracketCase {
      batchSchedulingService.acquireTask.orElse(batchSchedulingService.staleTask).value
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
        .product(OptionT.liftF(Clock[F].timestamp))
        .flatMap {
          case (task, timestamp) =>
            ApplicativeError[OptionT[F, *], Throwable].recoverWith {
              OptionT(
                transaction(
                  workerDao.assignTask(worker.id, task.videoMetadata.id, workerConfiguration.owner, timestamp)
                )
              ).product {
                  OptionT.liftF {
                    batchSchedulingService.publishScheduledVideoDownload(task.videoMetadata.id)
                  }
                }
                .as(timestamp -> task)
            } {
              case throwable: Throwable =>
                OptionT
                  .liftF(
                    logger.warn[F](
                      s"Error assigning task to worker. workerId=${worker.id}, scheduledVideoId=${task.videoMetadata.id} error=$throwable"
                    )
                  )
                  .productR(OptionT.none)
            }
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
                      Set[SchedulingStatus](
                        SchedulingStatus.Error,
                        SchedulingStatus.WorkersPaused,
                        SchedulingStatus.Stale,
                        SchedulingStatus.Paused,
                        SchedulingStatus.Deleted
                      ).contains(value.status) && value.lastUpdatedAt.isAfter(timestamp)
                    }
                    .merge {
                      workerStatusUpdates.map { workerStatusUpdate: WorkerStatusUpdate =>
                        workerStatusUpdate.workerStatus == WorkerStatus.Paused
                      }
                    }
                    .filter(identity),
                  5
                )
                .map[Option[Video]](Some.apply)
            } {
              case PausedVideoDownload =>
                logger.info[F](s"${scheduledVideoDownload.videoMetadata.url} has been paused/cancelled").as(None)
            }
        }
        .value
    } {
      case (Some(scheduledVideoDownload), Errored(exception)) =>
        batchSchedulingService
          .recordFailure(scheduledVideoDownload.videoMetadata.id, exception)
          .productR {
            logger.error[F](
              s"Error occurred in worker executor for scheduledVideoDownload ID=${scheduledVideoDownload.videoMetadata.id}",
              exception
            )
          }

      case (Some(scheduledVideoDownload), Canceled()) =>
        batchSchedulingService
          .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Queued)
          .productR(Applicative[F].unit)

      case (_, Errored(exception)) =>
        logger.error[F]("Error occurred without scheduledVideoDownload", exception)

      case (_, _) => Applicative[F].unit
    }

  private def releaseWorker(worker: Worker): F[Unit] =
    transaction(workerDao.releaseWorker(worker.id, workerConfiguration.owner)).flatMap {
      case Some(_) => Applicative[F].unit

      // The worker was freed as stale, or paused, while this instance was using it and may have been claimed by
      // another instance since. It is no longer this instance's to release.
      case None =>
        logger.warn[F](s"Worker is no longer owned by ${workerConfiguration.owner}, not releasing it. ID = ${worker.id}")
    }

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
    // A failure in any of the background streams would otherwise end `run` and take the batch application down with
    // it. Each stream handles the failures of a single tick or message itself, and is restarted if it fails anyway.
    idleWorkers
      .concurrently {
        restartOnFailure("Scheduled video download updates subscription") {
          publishScheduledVideoDownloadsUpdatesToTopic(scheduledVideoDownloadsTopic)
        }
      }
      .concurrently {
        restartOnFailure("Worker status updates subscription") {
          publishWorkerStatusUpdatesToTopic(workerStatusUpdatesTopic)
        }
      }
      .concurrently {
        restartOnFailure("Scan videos commands subscription") {
          publishScanForVideosCommandsToTopic(scanVideosCommandTopic)
        }
      }
      .concurrently(restartOnFailure("Stale scheduled video downloads clean up")(cleanUpStaleScheduledVideoDownloads))
      .concurrently(restartOnFailure("Video watch times update")(updateVideoWatchTimes))
      .concurrently(restartOnFailure("Stale workers clean up")(cleanUpStaleWorkers))
      .concurrently(restartOnFailure("Duplicate detection")(duplicateDetection))
      .concurrently {
        maybeMessageTransaction.fold[Stream[F, Unit]](Stream.empty) { messageTransaction =>
          restartOnFailure("Doobie stream messages clean up")(cleanUpDoobieStreamMessages(messageTransaction))
        }
      }
      .concurrently {
        restartOnFailure("Scheduled video deletions") {
          performScheduledVideoDeletions(scheduledVideoDownloadsTopic.subscribe(Int.MaxValue))
        }
      }
      .concurrently {
        restartOnFailure("Worker status updates") {
          updateWorkersAndScheduledVideoDownloads(workerStatusUpdatesTopic.subscribe(Int.MaxValue))
        }
      }
      .concurrently {
        restartOnFailure("Video scanning") {
          scanVideosCommandTopic.subscribe(Int.MaxValue).evalMap { _ =>
            logFailure("Unable to synchronize the videos")(synchronizationService.sync)
          }
        }
      }
      .parEvalMapUnordered(workerConfiguration.maxConcurrentDownloads) { idleWorker =>
        performWorkDuringWorkPeriod(
          idleWorker,
          scheduledVideoDownloadsTopic.subscribe(Int.MaxValue),
          workerStatusUpdatesTopic.subscribe(Int.MaxValue)
        )
      }
      .collect {
        case Some(video) => video
      }

  private val duplicateDetection =
    Stream
      .eval {
        logger.info[F]("Starting duplicate detection")
          .productR {
            MonadThrow[F].recoverWith {
              duplicateDetectionService.run
                .productL(logger.info[F]("Duplicate detection completed"))
            } {
              case exception =>
                logger.error[F]("There was an error while duplicate detection", exception)
            }

          }
      }
      .delayBy(2 minute)
      .productL(Stream.sleep(15 minutes))
      .repeat

  private def performScheduledVideoDeletions(
    scheduledVideoDownloadUpdates: Stream[F, ScheduledVideoDownload]
  ): Stream[F, ScheduledVideoDownload] =
    scheduledVideoDownloadUpdates
      .filter { scheduledVideoDownload =>
        scheduledVideoDownload.status == SchedulingStatus.Deleted
      }
      .flatMap { scheduledVideoDownload =>
        Stream
          .eval(batchSchedulingService.deleteById(scheduledVideoDownload.videoMetadata.id))
          .recoverWith {
            case throwable =>
              Stream
                .eval(logger.error[F](s"Unable to perform deletion for $scheduledVideoDownload", throwable))
                .productR(Stream.empty)
          }
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

      logFailure(s"Unable to apply the worker status update to ${workerStatusUpdate.workerStatus}") {
        updateScheduledVideoDownloads.productR {
          transaction(workerDao.updateWorkerStatuses(workerStatusUpdate.workerStatus))
        }
      }.map(_.getOrElse(Seq.empty))
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

  /**
    * Records are committed once they have been processed, so a crash before the commit replays them (at-least-once).
    * A failure is retried with a bounded exponential backoff, to ride out transient database errors without
    * committing the record. A record that still fails after that, or refers to a video that no longer exists, is
    * logged and skipped. Retrying it forever would block the whole partition behind a single poison message.
    */
  private val updateVideoWatchTimes: Stream[F, Unit] =
    videoWatchMetricsSubscriber
      .subscribe("batch-scheduler")
      .evalTap { record =>
        processVideoWatchMetric(videoWatchMetricsSubscriber.extractValue(record))
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

  private def processVideoWatchMetric(videoWatchMetric: VideoWatchMetric): F[Unit] =
    retryTransientFailures(s"Fetching the video for $videoWatchMetric") {
      batchVideoService.fetchByVideoFileResourceId(videoWatchMetric.videoFileResourceId)
    }.flatMap { video =>
        val watchDuration =
          FiniteDuration(
            math.round(
              (videoWatchMetric.size.toDouble / video.fileResource.size) * video.videoMetadata.duration.toMillis
            ),
            TimeUnit.MILLISECONDS
          )

        // Each step is retried on its own, so that a failure in a later step does not repeat the earlier ones
        retryTransientFailures(s"Incrementing the watch time for $videoWatchMetric") {
          batchVideoService.incrementWatchTime(video.videoMetadata.id, watchDuration)
        }.productR {
          retryTransientFailures(s"Adding the watch history for $videoWatchMetric") {
            videoWatchHistoryService.addWatchHistory(
              videoWatchMetric.userId,
              video.videoMetadata.id,
              videoWatchMetric.timestamp,
              watchDuration
            )
          }
        }
      }
      .handleErrorWith { throwable =>
        logger.error[F](s"Skipping the video watch metric $videoWatchMetric, it could not be processed", throwable)
      }

  private def retryTransientFailures[A](description: String, attempt: Int = 0)(fa: F[A]): F[A] =
    fa.handleErrorWith {
      // The video has been deleted, so retrying cannot succeed
      case resourceNotFoundException: ResourceNotFoundException =>
        ApplicativeError[F, Throwable].raiseError(resourceNotFoundException)

      case throwable if attempt < VideoWatchMetricMaxRetries =>
        val delay = VideoWatchMetricRetryBaseDelay * (1L << attempt)

        logger
          .warn[F] {
            s"$description failed, retrying in $delay. " +
              s"retry=${attempt + 1}/$VideoWatchMetricMaxRetries, error=${throwable.getMessage}"
          }
          .productR(Async[F].sleep(delay))
          .productR(retryTransientFailures(description, attempt + 1)(fa))

      case throwable => ApplicativeError[F, Throwable].raiseError(throwable)
    }

  private def logFailure[A](description: String)(fa: F[A]): F[Option[A]] =
    fa.map(Option.apply).handleErrorWith { throwable =>
      logger.error[F](description, throwable).as(None)
    }

  private def restartOnFailure[A](name: String)(stream: Stream[F, A]): Stream[F, A] =
    stream.handleErrorWith { throwable =>
      Stream.exec(logger.error[F](s"$name failed, restarting it in $StreamRestartDelay", throwable)) ++
        restartOnFailure(name)(stream).delayBy(StreamRestartDelay)
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
          scanForVideosCommandSubscriber.commit(List(record)).as(scanForVideosCommandSubscriber.extractValue(record))
        }
    }

  private def cleanUpDoobieStreamMessages(messageTransaction: T ~> F): Stream[F, Unit] =
    Stream
      .eval(Clock[F].timestamp)
      .evalMap { timestamp =>
        logFailure("Unable to delete the doobie stream messages") {
          messageTransaction {
            messageDao.deleteBefore(timestamp.minus(30, ChronoUnit.MINUTES))

          }.flatMap { deletedMessageCount =>
            logger.info[F](s"Deleted $deletedMessageCount doobie stream messages")
          }
        }.void
      }
      .delayBy(5 minutes)
      .repeat

  private val cleanUpStaleScheduledVideoDownloads: Stream[F, ScheduledVideoDownload] =
    Stream
      .eval {
        logFailure("Unable to mark the timed out scheduled video downloads as stale") {
          batchSchedulingService.updateTimedOutTasks(2 minutes)
        }
      }
      .repeat
      .metered(30 seconds)
      .flatMap(staleDownloads => Stream.emits(staleDownloads.getOrElse(Seq.empty)))

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

  private val cleanUpStaleWorkers: Stream[F, Worker] =
    Stream
      .eval(logger.info[F]("cleanUpStaleWorkersTask started"))
      .productR {
        Stream
          .eval(Clock[F].timestamp)
          .repeat
          .metered(30 seconds)
          .evalMap { timestamp =>
            logFailure("Unable to clean up the stale workers") {
              transaction {
                workerDao.cleanUpStaleWorkers(timestamp.minus(Duration.ofMinutes(5)))
              }
            }
          }
          .flatMap(staleWorkers => Stream.emits(staleWorkers.getOrElse(Seq.empty)))
      }

  override val init: F[Unit] =
    logger
      .info[F]("Batch initialization started")
      .productR(initWorkers)
      .flatMap(count => logger.info[F](s"New workers created: $count"))
      .productL(logger.info[F]("Batch initialization completed"))
}

object SchedulerImpl {
  val WorkerPollPeriod: FiniteDuration = 1 second

  private val StreamRestartDelay: FiniteDuration = 10 seconds

  private val VideoWatchMetricMaxRetries = 3
  private val VideoWatchMetricRetryBaseDelay: FiniteDuration = 1 second

  private def isWorkPeriod[F[_]: Clock: Applicative](start: LocalTime, end: LocalTime): F[Boolean] =
    if (start == end)
      Applicative[F].pure(true)
    else
      Clock[F].timestamp
        .map { timestamp =>
          val localTime = timestamp.atZone(ZoneId.systemDefault()).toLocalTime

          if (start.isBefore(end))
            localTime.isAfter(start) && localTime.isBefore(end)
          else
            localTime.isAfter(start) || localTime.isBefore(end)
        }
}
