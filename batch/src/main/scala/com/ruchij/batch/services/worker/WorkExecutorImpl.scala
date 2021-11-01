package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.kernel.{Deferred, MonadCancelThrow}
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
import fs2.concurrent.Topic

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
//      case _: CustomVideoSite =>
//        Resource
//          .eval(videoAnalysisService.downloadUri(scheduledVideoDownload.videoMetadata.url))
//          .flatMap { downloadUri =>
//            val videoFileName = downloadUri.path.segments.lastOption.map(_.encoded).getOrElse("video.unknown")
//            val videoPath =
//              s"${storageConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}-$videoFileName"
//
//            downloadService.download(downloadUri, videoPath)
//          }
//          .flatMap { downloadResult =>
//            Resource
//              .eval(JodaClock[F].timestamp)
//              .map { timestamp =>
//                (downloadResult.data, Applicative[F].pure {
//                  FileResource(
//                    scheduledVideoDownload.videoMetadata.id,
//                    timestamp,
//                    downloadResult.downloadedFileKey,
//                    downloadResult.mediaType,
//                    downloadResult.size
//                  )
//                })
//              }
//          }

      case _ =>
        val videoFilePath = s"${storageConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}"

        Resource.pure[F, (Stream[F, Long], F[FileResource])] {
          (
            Stream
              .eval(Topic[F, YTDownloaderProgress])
              .flatMap { topic =>
                val progressStream: Stream[F, YTDownloaderProgress] = topic.subscribe(Int.MaxValue)

                Stream
                  .eval(Deferred[F, Either[Throwable, Unit]])
                  .flatMap { deferred =>
                    progressStream
                      .concurrently {
                        topic.publish {
                          youTubeVideoDownloader
                            .downloadVideo(scheduledVideoDownload.videoMetadata.url, videoFilePath)
                            .onFinalizeCase {
                              case ExitCase.Errored(throwable) => deferred.complete(Left(throwable)).as((): Unit)
                              case _ => deferred.complete(Right((): Unit)).as((): Unit)
                            }
                        }
                      }
                      .concurrently {
                        progressStream
                          .groupWithin(Int.MaxValue, 10 seconds)
                          .evalMap { chunk =>
                            OptionT
                              .fromOption[F](chunk.last)
                              .semiflatMap { progress =>
                                if (scheduledVideoDownload.videoMetadata.size < math.round(progress.totalSize.bytes)) {
                                  transaction {
                                    videoMetadataDao.update(
                                      scheduledVideoDownload.videoMetadata.id,
                                      None,
                                      Some(math.round(progress.totalSize.bytes))
                                    )
                                  }.as((): Unit)
                                } else Applicative[F].unit
                              }
                              .value
                          }
                          .interruptWhen(deferred)
                      }
                      .interruptWhen(deferred)
                      .map(progress => math.round((progress.completed / 100) * progress.totalSize.bytes))
                  }
              },
            OptionT {
              repositoryService
                .list(storageConfiguration.videoFolder)
                .find(
                  fileKey => fileKey.split('/').lastOption.exists(_.startsWith(scheduledVideoDownload.videoMetadata.id))
                )
                .compile
                .last
            }.flatMap { file =>
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

  def downloadVideo(
    workerId: String,
    scheduledVideoDownload: ScheduledVideoDownload,
    interrupt: Stream[F, Boolean]
  ): F[FileResource] =
    download(scheduledVideoDownload)
      .use {
        case (data, fileResourceF) =>
          data
            .observe {
              _.groupWithin(Int.MaxValue, 30 seconds).evalMap { _ =>
                JodaClock[F].timestamp.flatMap { timestamp =>
                  transaction(workerDao.updateHeartBeat(workerId, timestamp))
                    .productR(Applicative[F].unit)
                }
              }
                .productR(Stream.empty)
            }
            .evalMap { byteCount =>
              batchSchedulingService.publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, byteCount)
            }
            .interruptWhen(interrupt)
            .compile
            .drain
            .productR {
              fileResourceF.flatTap { fileResource =>
                transaction(fileResourceDao.insert(fileResource))
              }
            }
      }

  override def execute(
    scheduledVideoDownload: ScheduledVideoDownload,
    worker: Worker,
    interrupt: Stream[F, Boolean]
  ): F[Video] =
    logger
      .info[F](s"Worker ${worker.id} started download for ${scheduledVideoDownload.videoMetadata.url}")
      .productR {
        downloadVideo(worker.id, scheduledVideoDownload, interrupt)
          .flatMap { fileResource =>
            repositoryService.size(fileResource.path).flatMap { maybeSizeFile =>
              maybeSizeFile
                .fold[F[Video]](
                  ApplicativeError[F, Throwable]
                    .raiseError(ResourceNotFoundException(s"File not found at: ${fileResource.path}"))
                ) { fileSize =>
                  if (fileSize < scheduledVideoDownload.videoMetadata.size && CustomVideoSite.values
                      .contains(scheduledVideoDownload.videoMetadata.videoSite))
                    logger
                      .warn[F](
                        s"Worker ${worker.id} invalidly deemed as complete: ${scheduledVideoDownload.videoMetadata.url}"
                      )
                      .productR(execute(scheduledVideoDownload, worker, interrupt))
                  else
                    batchSchedulingService
                      .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Downloaded)
                      .productL {
                        batchSchedulingService
                          .publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, fileSize)
                      }
                      .productR(batchVideoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource.id))
                      .productL {
                        if (fileSize > scheduledVideoDownload.videoMetadata.size) {
                          batchVideoService.update(scheduledVideoDownload.videoMetadata.id, fileSize)
                        } else Applicative[F].unit
                      }
                      .flatTap { video =>
                        MonadCancelThrow[F]
                          .handleErrorWith(videoEnrichmentService.videoSnapshots(video).as((): Unit)) { _ =>
                            Applicative[F].unit
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
      }
}
