package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.implicits._
import cats.{Applicative, ~>}
import com.ruchij.batch.config.BatchStorageConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.CustomVideoSite
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService, YouTubeVideoDownloader}
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.MediaType

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class WorkExecutorImpl[F[_]: Concurrent: Timer, T[_]](
  fileResourceDao: FileResourceDao[T],
  workerDao: WorkerDao[T],
  repositoryService: RepositoryService[F],
  batchSchedulingService: BatchSchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  videoService: VideoService[F],
  downloadService: DownloadService[F],
  youTubeVideoDownloader: YouTubeVideoDownloader[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  storageConfiguration: BatchStorageConfiguration
)(implicit transaction: T ~> F)
    extends WorkExecutor[F] {

  private val logger = Logger[WorkExecutorImpl[F, T]]

  def download(scheduledVideoDownload: ScheduledVideoDownload): Resource[F, DownloadResult[F]] =
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

      case _ =>
        val videoPath = s"${storageConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}.mp4"

        Resource.eval(Sync[F].delay(Paths.get(videoPath)))
          .map {
            path =>
              DownloadResult(
                scheduledVideoDownload.videoMetadata.url,
                videoPath,
                scheduledVideoDownload.videoMetadata.size,
                MediaType.video.mp4,
                youTubeVideoDownloader.downloadVideo(scheduledVideoDownload.videoMetadata.url, path)
            )
          }
    }

  def downloadVideo(
    workerId: String,
    scheduledVideoDownload: ScheduledVideoDownload,
    interrupt: Stream[F, Boolean]
  ): F[(FileResource, DownloadResult[F])] =
    download(scheduledVideoDownload)
      .use { downloadResult =>
        OptionT(transaction(fileResourceDao.findByPath(downloadResult.downloadedFileKey)))
          .getOrElseF {
            JodaClock[F].timestamp
              .flatMap { timestamp =>
                val fileResource =
                  FileResource(
                    scheduledVideoDownload.videoMetadata.id,
                    timestamp,
                    downloadResult.downloadedFileKey,
                    downloadResult.mediaType,
                    downloadResult.size
                  )

                transaction(fileResourceDao.insert(fileResource)).as(fileResource)
              }
          }
          .product {
            downloadResult.data
              .observe {
                _.groupWithin(Int.MaxValue, 30 seconds).evalMap { _ =>
                  JodaClock[F].timestamp.flatMap { timestamp =>
                    transaction(workerDao.updateHeartBeat(workerId, timestamp))
                      .productR(Applicative[F].unit)
                  }
                }
              }
              .evalMap { byteCount =>
                batchSchedulingService.publishDownloadProgress(scheduledVideoDownload.videoMetadata.id, byteCount)
              }
              .interruptWhen(interrupt)
              .compile
              .drain
              .as(downloadResult)
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
          .flatMap {
            case (fileResource, _) =>
              repositoryService.size(fileResource.path).flatMap {
                _.filter { _ >= scheduledVideoDownload.videoMetadata.size }
                  .fold[F[Video]] {
                    logger
                      .warn[F](
                        s"Worker ${worker.id} invalidly deemed as complete: ${scheduledVideoDownload.videoMetadata.url}"
                      )
                      .productR(execute(scheduledVideoDownload, worker, interrupt))
                  } { fileSize =>
                    batchSchedulingService
                      .updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Downloaded)
                      .productL {
                        if (fileSize > scheduledVideoDownload.videoMetadata.size)
                          videoService.update(scheduledVideoDownload.videoMetadata.id, None, Some(fileSize))
                        else Applicative[F].unit
                      }
                      .productR(videoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource.id))
                      .flatTap(videoEnrichmentService.videoSnapshots)
                      .productL(
                        batchSchedulingService.completeScheduledVideoDownload(scheduledVideoDownload.videoMetadata.id)
                      )
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
