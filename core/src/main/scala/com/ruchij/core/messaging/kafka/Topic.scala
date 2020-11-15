package com.ruchij.core.messaging.kafka

import cats.effect.Sync
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.kafka.Codecs._
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.kafka.vulcan.{AvroSettings, SchemaRegistryClientSettings, avroDeserializer, avroSerializer}
import fs2.kafka.{RecordDeserializer, RecordSerializer}
import vulcan.Codec
import vulcan.generic._

trait Topic[A] {
  val name: String

  val codec: Codec[A]

  def serializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): RecordSerializer[F, A] =
    avroSerializer[A](codec).using {
      AvroSettings { SchemaRegistryClientSettings(kafkaConfiguration.schemaRegistry.renderString) }
    }

  def deserializer[F[_]: Sync](kafkaConfiguration: KafkaConfiguration): RecordDeserializer[F, A] =
    avroDeserializer[A](codec).using {
      AvroSettings { SchemaRegistryClientSettings(kafkaConfiguration.schemaRegistry.renderString) }
    }
}

object Topic {
  case object ScheduledVideoDownloadTopic extends Topic[ScheduledVideoDownload] {
    override val name: String = "scheduled-video-downloads"

    override val codec: Codec[ScheduledVideoDownload] = Codec.derive[ScheduledVideoDownload]
  }

  case object DownloadProgressTopic extends Topic[DownloadProgress] {
    override val name: String = "download-progress-topic"

    override val codec: Codec[DownloadProgress] = Codec.derive[DownloadProgress]
  }

}
