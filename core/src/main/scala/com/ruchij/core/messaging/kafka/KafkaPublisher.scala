package com.ruchij.core.messaging.kafka

import cats.effect.{Async, Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher
import fs2.kafka._
import fs2.{Pipe, Stream}

class KafkaPublisher[F[_]: Sync, A](topicName: String, kafkaProducer: KafkaProducer.Metrics[F, Unit, A])
    extends Publisher[F, A] {

  private val logger = Logger[KafkaPublisher[F, A]]

  override val publish: Pipe[F, A, Unit] =
    _.chunks
      .evalMap { chunk =>
        val producerRecords: List[ProducerRecord[Unit, A]] =
          chunk.map { value => ProducerRecord[Unit, A](topicName, (): Unit, value) }.toList

        kafkaProducer.produce(ProducerRecords[List, Unit, A](producerRecords))
          .productR {
            producerRecords.traverse {
              producerRecord =>
                logger.trace(s"Published: topic=${producerRecord.topic}, value=${producerRecord.value}")
            }
          }

      }
      .productR(Stream.empty)

  override def publishOne(input: A): F[Unit] =
    publish(Stream.emit[F, A](input)).compile.drain
}

object KafkaPublisher {
  def apply[F[_]: Async, A](kafkaConfiguration: KafkaConfiguration)(
    implicit topic: KafkaTopic[A]
  ): Resource[F, KafkaPublisher[F, A]] =
    KafkaProducer.resource {
      ProducerSettings[F, Unit, A](GenericSerializer[F, Unit], topic.serializer[F](kafkaConfiguration))
        .withBootstrapServers(kafkaConfiguration.bootstrapServers)
    }
      .map { producer =>
        new KafkaPublisher[F, A](topic.name, producer)
      }
}
