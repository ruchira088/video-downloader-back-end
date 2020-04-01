package com.ruchij.services.worker

import cats.Applicative
import cats.effect.Sync
import com.ruchij.config.DownloadConfiguration
import com.ruchij.services.download.DownloadService
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.video.{VideoAnalysisService, VideoService}

class WorkerFactoryImpl[F[_]: Sync](
  schedulingService: SchedulingService[F],
  videoAnalysisService: VideoAnalysisService[F],
  videoService: VideoService[F],
  downloadService: DownloadService[F],
  downloadConfiguration: DownloadConfiguration
) extends WorkerFactory[F, WorkerImpl[F]] {

  override val newWorker: F[WorkerImpl[F]] =
    Applicative[F].pure {
      new WorkerImpl[F](schedulingService, videoAnalysisService, videoService, downloadService, downloadConfiguration)
    }
}
