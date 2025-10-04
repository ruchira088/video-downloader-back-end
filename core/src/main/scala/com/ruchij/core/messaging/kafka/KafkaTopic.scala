package com.ruchij.core.messaging.kafka

import cats.effect.Sync
import cats.effect.kernel.Resource
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.kafka.Codecs._
import com.ruchij.core.messaging.models.{HttpMetric, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import fs2.kafka.vulcan.{AvroSettings, SchemaRegistryClientSettings, avroDeserializer, avroSerializer}
import fs2.kafka.{ValueDeserializer, ValueSerializer}
import vulcan.Codec
import vulcan.generic._

trait KafkaTopic[A] {
  val name: String

  val codec: Codec[A]

  def serializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueSerializer[F, A]] =
    avroSerializer[A](codec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }

  def deserializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueDeserializer[F, A]] =
    avroDeserializer[A](codec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }
}

object KafkaTopic {
  implicit case object ScheduledVideoDownloadTopic extends KafkaTopic[ScheduledVideoDownload] {
    override val name: String = "scheduled-video-downloads"

    override val codec: Codec[ScheduledVideoDownload] = Codec.derive[ScheduledVideoDownload]
  }

  implicit case object DownloadProgressTopic extends KafkaTopic[DownloadProgress] {
    override val name: String = "download-progress-topic"

    override val codec: Codec[DownloadProgress] = Codec.derive[DownloadProgress]
  }

  implicit case object HttpMetricTopic extends KafkaTopic[HttpMetric] {
    override val name: String = "http-metrics"

    override val codec: Codec[HttpMetric] = Codec.derive[HttpMetric]
  }

  implicit case object WorkerStatusUpdateTopic extends KafkaTopic[WorkerStatusUpdate] {
    override val name: String = "worker-status-updates"

    override val codec: Codec[WorkerStatusUpdate] = Codec.derive[WorkerStatusUpdate]
  }

  implicit case object ScanVideoCommandTopic extends KafkaTopic[ScanVideosCommand] {
    override val name: String = "scan-videos-command"

    override val codec: Codec[ScanVideosCommand] = Codec.derive[ScanVideosCommand]
  }

  implicit case object VideoWatchMetricTopic extends KafkaTopic[VideoWatchMetric] {

    override val name: String = "video-watch-metric"

    override val codec: Codec[VideoWatchMetric] = Codec.derive[VideoWatchMetric]
  }
}
