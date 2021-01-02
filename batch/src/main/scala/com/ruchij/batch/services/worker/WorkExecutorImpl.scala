package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.{Applicative, ~>}
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.core.config.DownloadConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.workers.WorkerDao
import com.ruchij.core.daos.workers.models.Worker
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class WorkExecutorImpl[F[_]: Concurrent: Timer, T[_]](
  fileResourceDao: FileResourceDao[T],
  workerDao: WorkerDao[T],
  repositoryService: RepositoryService[F],
  schedulingService: SchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  videoService: VideoService[F],
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: T ~> F)
    extends WorkExecutor[F] {

  private val logger = Logger[F, WorkExecutorImpl[F, T]]

  def downloadVideo(workerId: String, videoId: String, downloadUri: Uri, interrupt: Stream[F, Boolean]): F[(FileResource, DownloadResult[F])] = {
    val videoFileName = downloadUri.path.split("/").lastOption.getOrElse("video.unknown")
    val videoPath =
      s"${downloadConfiguration.videoFolder}/$videoId-$videoFileName"

    downloadService
      .download(downloadUri, videoPath)
      .use { downloadResult =>
        OptionT(transaction(fileResourceDao.findByPath(videoPath)))
          .getOrElseF {
            hashingService
              .hash(downloadResult.uri.renderString)
              .product(JodaClock[F].timestamp)
              .flatMap {
                case (fileKey, timestamp) =>
                  val fileResource =
                    FileResource(fileKey, timestamp, videoPath, downloadResult.mediaType, downloadResult.size)

                  transaction(fileResourceDao.insert(fileResource)).as(fileResource)
              }
          }
          .product {
            downloadResult.data
              .observe {
                _.groupWithin(Int.MaxValue, 30 seconds).evalMap {
                  _ => JodaClock[F].timestamp.flatMap { timestamp =>
                    transaction(workerDao.updateHeartBeat(workerId, timestamp))
                      .productR(Applicative[F].unit)
                  }
                }
              }
              .evalMap { byteCount =>
                schedulingService.publishDownloadProgress(videoId, byteCount)
              }
              .interruptWhen(interrupt)
              .compile
              .drain
              .as(downloadResult)
          }
      }
  }

  override def execute(scheduledVideoDownload: ScheduledVideoDownload, worker: Worker, interrupt: Stream[F, Boolean]): F[Video] =
    logger
      .infoF(s"Worker ${worker.id} started download for ${scheduledVideoDownload.videoMetadata.url}")
      .productR {
        videoAnalysisService
          .downloadUri(scheduledVideoDownload.videoMetadata.url)
          .flatMap { downloadUri => downloadVideo(worker.id, scheduledVideoDownload.videoMetadata.id, downloadUri, interrupt) }
          .flatMap {
            case (fileResource, _) =>
              repositoryService.size(fileResource.path).flatMap {
                _.filter { _ == scheduledVideoDownload.videoMetadata.size }
                  .fold[F[Video]](execute(scheduledVideoDownload, worker, interrupt)) { _ =>
                    schedulingService.updateStatus(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Downloaded)
                      .productR(videoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource.id))
                      .flatTap(videoEnrichmentService.videoSnapshots)
                      .productL(schedulingService.completeTask(scheduledVideoDownload.videoMetadata.id))
                      .productL {
                        logger.infoF(s"Worker ${worker.id} completed download for ${scheduledVideoDownload.videoMetadata.url}")
                      }
                  }
              }
          }
      }
}
