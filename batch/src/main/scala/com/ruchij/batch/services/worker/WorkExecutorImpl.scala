package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource.ExitCase
import cats.effect.{Async, Ref, Resource, Temporal}
import cats.implicits._
import cats.{Applicative, ApplicativeError, ~>}
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutorImpl.HeartBeatPeriod
import com.ruchij.batch.utils.Constants
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.CustomVideoSite
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.models.YTDownloaderProgress
import com.ruchij.core.services.video.{VideoAnalysisService, YouTubeVideoDownloader}
import com.ruchij.core.types.Clock
import fs2.concurrent.{SignallingRef, Topic}
import fs2.{Pipe, Stream}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

class WorkExecutorImpl[F[_]: Async: Clock, T[_]](
  fileResourceDao: FileResourceDao[T],
  workerDao: WorkerDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  repositoryService: RepositoryService[F],
  batchSchedulingService: BatchSchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  batchVideoService: BatchVideoService[F],
  downloadService: DownloadService[F],
  youTubeVideoDownloader: YouTubeVideoDownloader[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  storageConfiguration: StorageConfiguration
)(implicit transaction: T ~> F)
    extends WorkExecutor[F] {

  private val logger = Logger[WorkExecutorImpl[F, T]]

  def download(scheduledVideoDownload: ScheduledVideoDownload): Resource[F, (Stream[F, Long], F[FileResource])] =
    scheduledVideoDownload.videoMetadata.videoSite match {
      case _: CustomVideoSite =>
        Resource
          .eval(videoAnalysisService.downloadUri(scheduledVideoDownload.videoMetadata.url))
          .flatMap { downloadUri =>
            val videoFileName = downloadUri.path.segments.lastOption.map(_.encoded).getOrElse("video.unknown")
            val videoPath =
              s"${storageConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}-$videoFileName"

            downloadService.download(downloadUri, videoPath)
          }
          .flatMap { downloadResult =>
            Resource
              .eval(Clock[F].timestamp)
              .map { timestamp =>
                (downloadResult.data, Applicative[F].pure {
                  FileResource(
                    scheduledVideoDownload.videoMetadata.id,
                    timestamp,
                    downloadResult.downloadedFileKey,
                    downloadResult.mediaType,
                    downloadResult.size
                  )
                })
              }
          }

      case _ =>
        val videoId = scheduledVideoDownload.videoMetadata.id
        val videoFilePath = s"${storageConfiguration.videoFolder}/$videoId"

        Resource.pure[F, (Stream[F, Long], F[FileResource])] {
          (
            Stream
              .eval(Topic[F, YTDownloaderProgress])
              .flatMap { topic =>
                Stream
                  .eval(Deferred[F, Either[Throwable, Unit]])
                  .flatMap { deferred =>
                    val progressStream: Stream[F, YTDownloaderProgress] = topic.subscribe(Int.MaxValue)

                    progressStream
                      .concurrently {
                        topic.publish {
                          youTubeVideoDownloader
                            .downloadVideo(scheduledVideoDownload.videoMetadata.url, videoFilePath)
                            .onFinalizeCase { exitCase =>
                              logger
                                .info[F](
                                  s"ExitCase = $exitCase for YouTubeDownloader uri=${scheduledVideoDownload.videoMetadata.url}"
                                )
                                .productR {
                                  exitCase match {
                                    case ExitCase.Errored(throwable) => deferred.complete(Left(throwable)).void
                                    case _ => deferred.complete(Right((): Unit)).void
                                  }
                                }
                            }
                        }
                      }
                      .concurrently {
                        progressStream
                          .through(WorkExecutorImpl.sampleLatest(10 seconds, repeatUnchanged = false))
                          .evalMap { progress =>
                            // Saving the size is bookkeeping, a failure to save it must not abort the download
                            logSideEffectFailure(s"Unable to save the size of $videoId") {
                              transaction {
                                videoMetadataDao.update(
                                  videoId,
                                  None,
                                  Some(
                                    math.min(
                                      math.round(progress.totalSize.bytes),
                                      scheduledVideoDownload.videoMetadata.size
                                    )
                                  ),
                                  None
                                )
                              }.void
                            }
                          }
                      }
                      .interruptWhen(deferred)
                      .map(progress => math.round((progress.completed / 100) * progress.totalSize.bytes))
                  }
              },
            OptionT
              .liftF(findVideoFile(videoFilePath, scheduledVideoDownload.videoMetadata.id))
              .flatMap { file =>
                for {
                  fileSize <- OptionT(repositoryService.size(file))
                  fileType <- OptionT(repositoryService.fileType(file))
                  timestamp <- OptionT.liftF(Clock[F].timestamp)
                } yield FileResource(scheduledVideoDownload.videoMetadata.id, timestamp, file, fileType, fileSize)
              }
              .getOrElseF {
                ApplicativeError[F, Throwable].raiseError {
                  ResourceNotFoundException(s"Unable to find file with prefix: $videoFilePath")
                }
              }
          )
        }
    }

  private def findVideoFile(fileKey: String, videoMetadataId: String): F[String] =
    logger
      .info[F](s"Searching for the fileKey=$fileKey for videoMetadataId=$videoMetadataId")
      .productR {
        OptionT(findVideoFileWithExtensions(fileKey, Constants.VideoFileExtensions))
          .getOrElseF {
            findVideoFileByCrawling(videoMetadataId)
          }
          .flatTap { file =>
            logger.info[F](s"Found file=$file for $videoMetadataId")
          }
      }

  private def findVideoFileWithExtensions(fileKey: String, fileExtensions: List[String]): F[Option[String]] =
    fileExtensions match {
      case Nil => Applicative[F].pure(None)
      case fileExtension :: otherFileExtensions =>
        val filePath = fileKey + "." + fileExtension

        repositoryService.size(filePath).flatMap {
          case None => findVideoFileWithExtensions(fileKey, otherFileExtensions)
          case _ => Applicative[F].pure(Some(filePath))
        }
    }

  private def findVideoFileByCrawling(videoMetadataId: String): F[String] =
    SignallingRef[F]
      .of(0)
      .flatMap { signallingRef =>
        repositoryService
          .list(storageConfiguration.videoFolder)
          .evalFilter { fileKey =>
            signallingRef
              .updateAndGet(_ + 1)
              .flatMap { fileCount =>
                if (fileCount % 10 == 0)
                  logger.info[F](s"Searched $fileCount files for the file key for $videoMetadataId")
                else Applicative[F].pure((): Unit)
              }
              .as {
                fileKey
                  .split('/')
                  .lastOption
                  .exists(_.startsWith(videoMetadataId))
              }
          }
          .compile
          .toList
          .flatMap[String] {
            case Nil =>
              val resourceNotFoundException = ResourceNotFoundException(s"Unable to find file key for $videoMetadataId")

              logger
                .error[F]("File key not found", resourceNotFoundException)
                .productR {
                  ApplicativeError[F, Throwable].raiseError(resourceNotFoundException)
                }

            case fileKey :: Nil => Applicative[F].pure(fileKey)

            case fileKeys =>
              val illegalStateException =
                new IllegalStateException(s"Multiple file keys found for $videoMetadataId. Keys=${fileKeys
                  .mkString("[", ", ", "]")}")

              logger
                .error[F]("Multiple file keys found", illegalStateException)
                .productR {
                  ApplicativeError[F, Throwable].raiseError(illegalStateException)
                }
          }
      }

  private def downloadVideo(
    workerId: String,
    scheduledVideoDownload: ScheduledVideoDownload,
    interrupt: Stream[F, Boolean]
  ): F[FileResource] =
    download(scheduledVideoDownload)
      .use {
        case (data, fileResourceF) =>
          // The published progress is what refreshes last_updated_at, and a download that is not refreshed for 2
          // minutes is marked as stale. The latest progress is therefore republished even while the downloader is
          // quiet, such as while yt-dlp merges the downloaded formats.
          data
            .through(WorkExecutorImpl.sampleLatest(3 seconds, repeatUnchanged = true))
            // A failure to publish the progress, such as the message broker being briefly unavailable, must not abort
            // a download that may have been running for hours. The progress is published again on the next tick.
            .evalTap { byteCount =>
              publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, byteCount)
            }
            // fs2 halts the stream gracefully on `true`, which would look like a finished download, so turn the
            // signal into an error that the scheduler recognises as a pause
            .interruptWhen {
              interrupt.flatMap { halt =>
                if (halt) Stream.raiseError[F](PausedVideoDownload) else Stream.emit(false)
              }
            }
            .compile
            .last
            .flatMap {
              case Some(byteCount) =>
                logger.info[F](
                  s"Worker $workerId reached byteCount=$byteCount for videoId=${scheduledVideoDownload.videoMetadata.id}"
                )

              case _ =>
                logger.info[F](
                  s"Worker $workerId didn't download any bytes for videoId=${scheduledVideoDownload.videoMetadata.id}"
                )
            }
            .productR(fileResourceF)
      }

  private def publishDownloadProgress(videoId: String, byteCount: Long): F[Unit] =
    logSideEffectFailure(s"Unable to publish the download progress of $videoId") {
      batchSchedulingService.publishDownloadProgress(videoId, byteCount)
    }

  private def logSideEffectFailure(description: String)(sideEffect: F[Unit]): F[Unit] =
    sideEffect.handleErrorWith(throwable => logger.warn[F](s"$description. error=$throwable"))

  /**
    * Keeps the worker alive for as long as it is in use. A failed heartbeat is logged and tried again on the next
    * tick, rather than silently ending the heartbeats.
    */
  private def heartBeats(worker: Worker): Stream[F, Unit] =
    worker.owner.fold[Stream[F, Unit]](Stream.exec(logger.warn[F](s"Worker ${worker.id} has no owner"))) { owner =>
      Stream.fixedRate[F](HeartBeatPeriod).evalMap { _ =>
        Clock[F].timestamp
          .flatMap { timestamp =>
            transaction(workerDao.updateHeartBeat(worker.id, owner, timestamp))
          }
          .flatMap {
            case None => logger.warn[F](s"Worker ${worker.id} is no longer owned by $owner, its heartbeat was ignored")
            case Some(_) => Applicative[F].unit
          }
          .handleErrorWith { throwable =>
            logger.error[F](s"Unable to update the heartbeat of worker ${worker.id}", throwable)
          }
      }
    }

  // The heartbeat runs for the whole execution, including the probing and snapshots after the download, so that the
  // worker is not freed as stale and claimed by another instance while it is still in use
  override def execute(
    scheduledVideoDownload: ScheduledVideoDownload,
    worker: Worker,
    interrupt: Stream[F, Boolean],
    retries: Int
  ): F[Video] =
    Async[F].background(heartBeats(worker).compile.drain).surround {
      executeTask(scheduledVideoDownload, worker, interrupt, retries)
    }

  private def executeTask(
    scheduledVideoDownload: ScheduledVideoDownload,
    worker: Worker,
    interrupt: Stream[F, Boolean],
    retries: Int
  ): F[Video] =
    logger
      .info[F](s"Worker ${worker.id} started download for ${scheduledVideoDownload.videoMetadata.url}")
      .productR {
        downloadVideo(worker.id, scheduledVideoDownload, interrupt)
          .flatTap { fileResource =>
            logger.info[F] {
              s"Worker ${worker.id} completed download for url=${scheduledVideoDownload.videoMetadata.url} " +
                s"path=${fileResource.path} size=${fileResource.size}"
            }
          }
          .flatMap { fileResource =>
            if (fileResource.size < scheduledVideoDownload.videoMetadata.size && CustomVideoSite.values
                .contains(scheduledVideoDownload.videoMetadata.videoSite) && retries > 0)
              logger
                .warn[F](
                  s"Worker ${worker.id} invalidly deemed as complete: ${scheduledVideoDownload.videoMetadata.url}"
                )
                .productR(executeTask(scheduledVideoDownload, worker, interrupt, retries - 1))
            else
              batchSchedulingService
                .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Downloaded)
                .productL {
                  if (scheduledVideoDownload.videoMetadata.duration.toMillis == 0)
                    videoAnalysisService
                      .videoDurationFromPath(fileResource.path)
                      .handleErrorWith { throwable =>
                        logger
                          .error[F](s"Failed infer video duration for path=${fileResource.path} error", throwable)
                          .productR(ApplicativeError[F, Throwable].raiseError(throwable))
                      }
                      .flatMap(
                        duration =>
                          transaction(
                            videoMetadataDao.update(scheduledVideoDownload.videoMetadata.id, None, None, Some(duration))
                        )
                      )
                      .void
                  else Applicative[F].unit
                }
                .productL(publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, fileResource.size))
                .productL(transaction(fileResourceDao.insert(fileResource)))
                .productR(batchVideoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource.id))
                .productL {
                  if (fileResource.size != scheduledVideoDownload.videoMetadata.size) {
                    batchVideoService.update(scheduledVideoDownload.videoMetadata.id, fileResource.size)
                  } else Applicative[F].unit
                }
                .flatTap { video =>
                  videoEnrichmentService
                    .videoSnapshots(video)
                    .attempt
                    .flatMap {
                      case Left(throwable) =>
                        logger.error[F](s"Failed to take video snapshots for video=$video", throwable)
                      case _ => Applicative[F].pure((): Unit)
                    }
                }
                .productL {
                  batchSchedulingService.completeScheduledVideoDownload(scheduledVideoDownload.videoMetadata.id)
                }
                .productL {
                  logger.info[F](
                    s"Worker ${worker.id} completed download for ${scheduledVideoDownload.videoMetadata.url}"
                  )
                }
          }
      }
}

object WorkExecutorImpl {
  private val HeartBeatPeriod: FiniteDuration = 30 seconds

  /**
    * Emits the latest value of the stream once every `period`, and the last value when the stream ends.
    *
    * With `repeatUnchanged`, the latest value is emitted on every tick even if the stream has not emitted since the
    * previous tick, so that a quiet stream still produces a value at least once every `period`.
    */
  private[worker] def sampleLatest[F[_]: Temporal, A](period: FiniteDuration, repeatUnchanged: Boolean): Pipe[F, A, A] =
    source =>
      for {
        // The latest value and whether it has changed since it was last emitted
        latest <- Stream.eval(Ref.of[F, (Option[A], Boolean)]((None, false)))
        sourceCompleted <- Stream.eval(SignallingRef[F, Boolean](false))

        takeLatest = (emitUnchanged: Boolean) =>
          latest.modify {
            case (value, changed) => ((value, false), if (changed || emitUnchanged) value else None)
          }

        value <- Stream
          .fixedRate[F](period)
          .evalMap(_ => takeLatest(repeatUnchanged))
          .unNone
          .interruptWhen(sourceCompleted)
          .append(Stream.eval(takeLatest(false)).unNone)
          .concurrently {
            source.foreach(value => latest.set((Some(value), true))) ++ Stream.exec(sourceCompleted.set(true))
          }
      } yield value
}
