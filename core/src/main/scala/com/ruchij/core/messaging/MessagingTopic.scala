package com.ruchij.core.messaging

import cats.effect.Sync
import cats.effect.kernel.Resource
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.models.{HttpMetric, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import fs2.kafka.vulcan.{AvroSettings, SchemaRegistryClientSettings, avroDeserializer, avroSerializer}
import fs2.kafka.{ValueDeserializer, ValueSerializer}
import io.circe.generic.semiauto.{deriveCodec => deriveJsonCodec}
import io.circe.{Codec => JsonCodec}
import vulcan.generic._
import AvroCodecs._
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import org.http4s.circe.decodeUri
import org.http4s.circe.encodeUri
import vulcan.{Codec => AvroCodec}

trait MessagingTopic[A] {
  val name: String

  val avroCodec: AvroCodec[A]

  val jsonCodec: JsonCodec[A]

  def serializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueSerializer[F, A]] =
    avroSerializer[A](avroCodec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }

  def deserializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueDeserializer[F, A]] =
    avroDeserializer[A](avroCodec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }
}

object MessagingTopic {
  implicit case object ScheduledVideoDownloadTopic extends MessagingTopic[ScheduledVideoDownload] {
    override val name: String = "scheduled-video-downloads"

    override val avroCodec: AvroCodec[ScheduledVideoDownload] = AvroCodec.derive[ScheduledVideoDownload]

    override val jsonCodec: JsonCodec[ScheduledVideoDownload] = deriveJsonCodec[ScheduledVideoDownload]
  }

  implicit case object DownloadProgressTopic extends MessagingTopic[DownloadProgress] {
    override val name: String = "download-progress-topic"

    override val avroCodec: AvroCodec[DownloadProgress] = AvroCodec.derive[DownloadProgress]

    override val jsonCodec: JsonCodec[DownloadProgress] = deriveJsonCodec[DownloadProgress]
  }

  implicit case object HttpMetricTopic extends MessagingTopic[HttpMetric] {
    override val name: String = "http-metrics"

    override val avroCodec: AvroCodec[HttpMetric] = AvroCodec.derive[HttpMetric]

    override val jsonCodec: JsonCodec[HttpMetric] = deriveJsonCodec[HttpMetric]
  }

  implicit case object WorkerStatusUpdateTopic extends MessagingTopic[WorkerStatusUpdate] {
    override val name: String = "worker-status-updates"

    override val avroCodec: AvroCodec[WorkerStatusUpdate] = AvroCodec.derive[WorkerStatusUpdate]

    override val jsonCodec: JsonCodec[WorkerStatusUpdate] = deriveJsonCodec[WorkerStatusUpdate]
  }

  implicit case object ScanVideoCommandTopic extends MessagingTopic[ScanVideosCommand] {
    override val name: String = "scan-videos-command"

    override val avroCodec: AvroCodec[ScanVideosCommand] = AvroCodec.derive[ScanVideosCommand]

    override val jsonCodec: JsonCodec[ScanVideosCommand] = deriveJsonCodec[ScanVideosCommand]
  }

  implicit case object VideoWatchMetricTopic extends MessagingTopic[VideoWatchMetric] {

    override val name: String = "video-watch-metric"

    override val avroCodec: AvroCodec[VideoWatchMetric] = AvroCodec.derive[VideoWatchMetric]

    override val jsonCodec: JsonCodec[VideoWatchMetric] = deriveJsonCodec[VideoWatchMetric]
  }
}
