package com.ruchij.core.messaging

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.kafka.Topic
import fs2.{Pipe, Stream}
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerSettings, RecordSerializer, producerResource}

class KafkaPublisher[F[_]: Sync, A](topicName: String, kafkaProducer: KafkaProducer.Metrics[F, Unit, A])
    extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    _.chunks
      .evalMap { chunk =>
        kafkaProducer.produce {
          ProducerRecords[List, Unit, A] {
            chunk
              .map { value =>
                ProducerRecord(topicName, (): Unit, value)
              }
              .toList
          }
        }
      }
      .productR(Stream.empty)

  override def publish(input: A): F[Unit] =
    publish(Stream.emit[F, A](input)).compile.drain
}

object KafkaPublisher {
  def apply[F[_]: ConcurrentEffect: ContextShift, A](kafkaConfiguration: KafkaConfiguration)(
    implicit topic: Topic[F, A]
  ): Resource[F, KafkaPublisher[F, A]] =
    producerResource {
      ProducerSettings[F, Unit, A](RecordSerializer[F, Unit], topic.serializer(kafkaConfiguration))
        .withBootstrapServers(kafkaConfiguration.bootstrapServers)
    }
      .map { producer =>
        new KafkaPublisher[F, A](topic.name, producer)
      }
}
