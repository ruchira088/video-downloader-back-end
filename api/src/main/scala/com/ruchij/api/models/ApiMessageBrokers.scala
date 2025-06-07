package com.ruchij.api.models

import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.models.{CommittableRecord, HttpMetric, VideoWatchMetric}
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}

final case class ApiMessageBrokers[F[_], M[_]](
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
  scheduledVideoDownloadPubSub: PubSub[F, CommittableRecord[M, *], ScheduledVideoDownload],
  healthCheckPubSub: PubSub[F, CommittableRecord[M, *], HealthCheckMessage],
  workerStatusUpdatesPublisher: Publisher[F, WorkerStatusUpdate],
  scanVideosCommandPublisher: Publisher[F, ScanVideosCommand],
  httpMetricsPublisher: Publisher[F, HttpMetric],
  videoWatchMetricsPublisher: Publisher[F, VideoWatchMetric]
)
