package com.ruchij.batch.services.worker

import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import cats.~>
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.core.config.DownloadConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.core.types.JodaClock

class WorkExecutorImpl[F[_]: Sync: Clock, T[_]](
  fileResourceDao: FileResourceDao[T],
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

  override def execute(scheduledVideoDownload: ScheduledVideoDownload): F[Video] =
    logger
      .infoF(s"Worker started download for ${scheduledVideoDownload.videoMetadata.url}")
      .productR {
        videoAnalysisService
          .downloadUri(scheduledVideoDownload.videoMetadata.url)
          .flatMap { downloadUri =>
            val videoFileName = downloadUri.path.split("/").lastOption.getOrElse("video.unknown")
            val videoPath =
              s"${downloadConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}-$videoFileName"

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
                      .evalMap { bytes =>
                        schedulingService.updateDownloadProgress(scheduledVideoDownload.videoMetadata.id, bytes)
                      }
                      .compile
                      .drain
                      .as(downloadResult)
                  }
              }
          }
          .productL {
            schedulingService.completeTask(scheduledVideoDownload.videoMetadata.id)
          }
          .flatMap {
            case (fileResource, _) =>
              videoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource.id)
          }
          .flatTap(videoEnrichmentService.videoSnapshots)
      }
      .productL {
        logger.infoF(s"Worker completed download for ${scheduledVideoDownload.videoMetadata.url}")
      }
}
