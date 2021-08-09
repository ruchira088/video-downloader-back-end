package com.ruchij.api.models

import com.ruchij.api.models.ApiMessageBrokers.Broker
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.messaging.{PubSub, Publisher}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}

case class ApiMessageBrokers[F[_]](
                                    downloadProgressPubSub: Broker[F, DownloadProgress],
                                    scheduledVideoDownloadPubSub: Broker[F, ScheduledVideoDownload],
                                    healthCheckPubSub: Broker[F, HealthCheckMessage],
                                    workerStatusUpdatesPubSub: Broker[F, WorkerStatusUpdate],
                                    metricsPublisher: Publisher[F, HttpMetric]
)

object ApiMessageBrokers {
  type Broker[F[_], A] = PubSub[F, CommittableRecord[F, *], A]
}
