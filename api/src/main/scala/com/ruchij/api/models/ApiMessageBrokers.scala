package com.ruchij.api.models

import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}

case class ApiMessageBrokers[F[_]](
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[F, *], DownloadProgress],
  scheduledVideoDownloadPublisher: Publisher[F, ScheduledVideoDownload],
  healthCheckPubSub: PubSub[F, CommittableRecord[F, *], HealthCheckMessage],
  workerStatusUpdatesPublisher: Publisher[F, WorkerStatusUpdate],
  metricsPublisher: Publisher[F, HttpMetric]
)
