package com.ruchij.services.worker

import cats.effect.Sync
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.video.models.Video
import com.ruchij.services.download.DownloadService
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.video.{VideoAnalysisService, VideoService}

class WorkExecutorImpl[F[_]: Sync](
  schedulingService: SchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  videoService: VideoService[F],
  downloadService: DownloadService[F],
  downloadConfiguration: DownloadConfiguration
) extends WorkExecutor[F] {

  override def execute(scheduledVideoDownload: ScheduledVideoDownload): F[Video] =
    videoAnalysisService
      .downloadUri(scheduledVideoDownload.videoMetadata.url)
      .flatMap { downloadUri =>
        downloadService
          .download(downloadUri, downloadConfiguration.videoFolderKey)
          .use { downloadResult =>
            downloadResult.data
              .evalMap { bytes =>
                schedulingService.updateDownloadProgress(scheduledVideoDownload.videoMetadata.key, bytes)
              }
              .compile
              .drain
              .as(downloadResult.key)
          }
      }
      .productL {
        schedulingService.completeTask(scheduledVideoDownload.videoMetadata.key)
      }
      .flatMap {
        path => videoService.insert(scheduledVideoDownload.videoMetadata, path)
      }
}
