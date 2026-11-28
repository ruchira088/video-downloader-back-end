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
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveCodec as deriveJsonCodec
import io.circe.{Codec as JsonCodec, Json}
import vulcan.generic.*
import com.ruchij.core.messaging.kafka.AvroCodecs.given
import com.ruchij.core.circe.Decoders.given
import com.ruchij.core.circe.Encoders.given
import org.http4s.circe.decodeUri
import org.http4s.circe.encodeUri
import vulcan.Codec as AvroCodec

trait MessagingTopic[A] {
  val name: String

  val avroCodec: AvroCodec[A]

  val jsonCodec: JsonCodec[A]

  def serializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueSerializer[F, A]] =
    avroSerializer[A](using avroCodec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }

  def deserializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): Resource[F, ValueDeserializer[F, A]] =
    avroDeserializer[A](using avroCodec).forValue {
      AvroSettings[F] { SchemaRegistryClientSettings[F](kafkaConfiguration.schemaRegistry.renderString) }
    }
}

object MessagingTopic {
  given ScheduledVideoDownloadTopic: MessagingTopic[ScheduledVideoDownload] {
    override val name: String = "scheduled-video-downloads"

    override val avroCodec: AvroCodec[ScheduledVideoDownload] = AvroCodec.derive[ScheduledVideoDownload]

    // Messages from replicas deployed before retries existed have no retryCount. Plain circe derivation ignores
    // Scala default values, so supply the default before decoding. A missing retryAfter already decodes as None.
    override val jsonCodec: JsonCodec[ScheduledVideoDownload] = {
      val derived = deriveJsonCodec[ScheduledVideoDownload]

      JsonCodec.from(
        derived.prepare {
          _.withFocus {
            _.mapObject(json => if (json.contains("retryCount")) json else json.add("retryCount", Json.fromInt(0)))
          }
        },
        derived
      )
    }
  }

  given DownloadProgressTopic: MessagingTopic[DownloadProgress] {
    override val name: String = "download-progress-topic"

    override val avroCodec: AvroCodec[DownloadProgress] = AvroCodec.derive[DownloadProgress]

    override val jsonCodec: JsonCodec[DownloadProgress] = deriveJsonCodec[DownloadProgress]
  }

  given HttpMetricTopic: MessagingTopic[HttpMetric] {
    override val name: String = "http-metrics"

    override val avroCodec: AvroCodec[HttpMetric] = AvroCodec.derive[HttpMetric]

    override val jsonCodec: JsonCodec[HttpMetric] = deriveJsonCodec[HttpMetric]
  }

  given WorkerStatusUpdateTopic: MessagingTopic[WorkerStatusUpdate] {
    override val name: String = "worker-status-updates"

    override val avroCodec: AvroCodec[WorkerStatusUpdate] = AvroCodec.derive[WorkerStatusUpdate]

    override val jsonCodec: JsonCodec[WorkerStatusUpdate] = deriveJsonCodec[WorkerStatusUpdate]
  }

  given ScanVideoCommandTopic: MessagingTopic[ScanVideosCommand] {
    override val name: String = "scan-videos-command"

    override val avroCodec: AvroCodec[ScanVideosCommand] = AvroCodec.derive[ScanVideosCommand]

    override val jsonCodec: JsonCodec[ScanVideosCommand] = deriveJsonCodec[ScanVideosCommand]
  }

  given VideoWatchMetricTopic: MessagingTopic[VideoWatchMetric] {

    override val name: String = "video-watch-metric"

    override val avroCodec: AvroCodec[VideoWatchMetric] = AvroCodec.derive[VideoWatchMetric]

    override val jsonCodec: JsonCodec[VideoWatchMetric] = deriveJsonCodec[VideoWatchMetric]
  }
}
