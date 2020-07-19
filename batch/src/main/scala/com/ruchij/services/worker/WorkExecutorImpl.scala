package com.ruchij.services.worker

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.video.models.Video
import com.ruchij.logging.Logger
import com.ruchij.services.download.DownloadService
import com.ruchij.services.enrichment.VideoEnrichmentService
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.video.{VideoAnalysisService, VideoService}
import org.joda.time.DateTime

class WorkExecutorImpl[F[_]: Sync: Clock](
  schedulingService: SchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  videoService: VideoService[F],
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  downloadConfiguration: DownloadConfiguration
) extends WorkExecutor[F] {

  private val logger = Logger[F, WorkExecutorImpl[F]]

  override def execute(scheduledVideoDownload: ScheduledVideoDownload): F[Video] =
    logger.infoF(s"Worker started download for ${scheduledVideoDownload.videoMetadata.url}")
      .productR {
        videoAnalysisService
          .downloadUri(scheduledVideoDownload.videoMetadata.url)
          .flatMap { downloadUri =>
            val videoFileName = downloadUri.path.split("/").lastOption.getOrElse("video.unknown")
            val videoPath = s"${downloadConfiguration.videoFolder}/${scheduledVideoDownload.videoMetadata.id}-$videoFileName"

            downloadService
              .download(downloadUri, videoPath)
              .use { downloadResult =>
                downloadResult.data
                  .evalMap { bytes =>
                    schedulingService.updateDownloadProgress(scheduledVideoDownload.videoMetadata.id, bytes)
                  }
                  .compile
                  .drain
                  .as(downloadResult)
              }
          }
          .productL {
            schedulingService.completeTask(scheduledVideoDownload.videoMetadata.id)
          }
          .flatMap { downloadResult =>
            for {
              timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
              fileKey <- hashingService.hash(downloadResult.uri.renderString)
              fileResource = FileResource(
                fileKey,
                new DateTime(timestamp),
                downloadResult.downloadedFileKey,
                downloadResult.mediaType,
                downloadResult.size
              )

              video <- videoService.insert(scheduledVideoDownload.videoMetadata.id, fileResource)
            } yield video
          }
          .flatTap(videoEnrichmentService.videoSnapshots)
      }
    .productL {
      logger.infoF(s"Worker completed download for ${scheduledVideoDownload.videoMetadata.url}")
    }
}
