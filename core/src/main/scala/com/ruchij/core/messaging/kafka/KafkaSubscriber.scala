package com.ruchij.core.messaging.kafka

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import fs2.Stream
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer, RecordDeserializer, consumerResource}

class KafkaSubscriber[F[_], A](topicName: String, kafkaConsumer: KafkaConsumer[F, Unit, A])
    extends Subscriber[F, CommittableRecord[F, *], A] {

  override val subscribe: Stream[F, CommittableRecord[F, A]] =
    Stream
      .eval(kafkaConsumer.subscribeTo(topicName))
      .productR {
        kafkaConsumer.stream.map { committableConsumerRecord =>
          CommittableRecord(committableConsumerRecord.record.value, committableConsumerRecord.offset.commit)
        }
      }

}

object KafkaSubscriber {
  case class CommittableRecord[F[_], A](value: A, commit: F[Unit])

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, A](
    kafkaConfiguration: KafkaConfiguration
  )(implicit topic: KafkaTopic[A]): Resource[F, KafkaSubscriber[F, A]] =
    consumerResource {
      ConsumerSettings[F, Unit, A](RecordDeserializer[F, Unit], topic.deserializer[F](kafkaConfiguration))
        .withBootstrapServers(kafkaConfiguration.bootstrapServers)
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
        .withGroupId(kafkaConfiguration.consumerGroupId)
    }
      .map { kafkaConsumer =>
        new KafkaSubscriber[F, A](topic.name, kafkaConsumer)
      }
}
