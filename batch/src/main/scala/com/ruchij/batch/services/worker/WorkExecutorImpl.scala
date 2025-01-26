package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource.ExitCase
import cats.effect.{Async, Resource}
import cats.implicits._
import cats.{Applicative, ApplicativeError, ~>}
import com.ruchij.batch.config.BatchStorageConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.utils.Constants
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
import com.ruchij.core.types.JodaClock
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class WorkExecutorImpl[F[_]: Async: JodaClock, T[_]](
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
  storageConfiguration: BatchStorageConfiguration
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
              .eval(JodaClock[F].timestamp)
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
        val videoFilePath = s"${storageConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}"

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
                                .info(
                                  s"ExitCase = $exitCase for YouTubeDownloader uri=${scheduledVideoDownload.videoMetadata.url}"
                                )
                                .productR {
                                  exitCase match {
                                    case ExitCase.Errored(throwable) => deferred.complete(Left(throwable)).as((): Unit)
                                    case _ => deferred.complete(Right((): Unit)).as((): Unit)
                                  }
                                }
                            }
                        }
                      }
                      .concurrently {
                        progressStream
                          .debounce(10 seconds)
                          .evalMap { progress =>
                            transaction {
                              videoMetadataDao.update(
                                scheduledVideoDownload.videoMetadata.id,
                                None,
                                Some(
                                  math.min(
                                    math.round(progress.totalSize.bytes),
                                    scheduledVideoDownload.videoMetadata.size
                                  )
                                ),
                                None
                              )
                            }.as((): Unit)
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
                  timestamp <- OptionT.liftF(JodaClock[F].timestamp)
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
      .info(s"Searching for the fileKey=$fileKey for videoMetadataId=$videoMetadataId")
      .productR {
        OptionT(findVideoFileWithExtensions(fileKey, Constants.VideoFileExtensions))
          .getOrElseF {
            findVideoFileByCrawling(videoMetadataId)
          }
          .flatTap { file =>
            logger.info(s"Found file=$file for $videoMetadataId")
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
                  logger.info(s"Searched $fileCount files for the file key for $videoMetadataId")
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
                .error("File key not found", resourceNotFoundException)
                .productR {
                  ApplicativeError[F, Throwable].raiseError(resourceNotFoundException)
                }

            case fileKey :: Nil => Applicative[F].pure(fileKey)

            case fileKeys =>
              val illegalStateException =
                new IllegalStateException(s"Multiple file keys found for $videoMetadataId. Keys=${fileKeys
                  .mkString("[", ", ", "]")}")

              logger
                .error("Multiple file keys found", illegalStateException)
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
          data
            .concurrently {
              Stream.fixedRate(30 seconds).evalMap { _ =>
                JodaClock[F].timestamp.flatMap { timestamp =>
                  transaction(workerDao.updateHeartBeat(workerId, timestamp))
                    .productR(Applicative[F].unit)
                }
              }
            }
            .debounce(250 milliseconds)
            .evalTap { byteCount =>
              batchSchedulingService.publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, byteCount)
            }
            .interruptWhen(interrupt)
            .compile
            .last
            .flatMap {
              case Some(byteCount) =>
                logger.info(
                  s"Worker $workerId reached byteCount=$byteCount for videoId=${scheduledVideoDownload.videoMetadata.id}"
                )

              case _ =>
                logger.info(
                  s"Worker $workerId didn't download any bytes for videoId=${scheduledVideoDownload.videoMetadata.id}"
                )
            }
            .productR(fileResourceF)
      }

  override def execute(
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
                .productR(execute(scheduledVideoDownload, worker, interrupt, retries - 1))
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
                      .as((): Unit)
                  else Applicative[F].unit
                }
                .productL {
                  batchSchedulingService
                    .publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, fileResource.size)
                }
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
                        logger.error(s"Failed to take video snapshots for video=$video", throwable)
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
