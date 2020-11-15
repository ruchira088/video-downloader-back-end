package com.ruchij.core.messaging.kafka

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import fs2.Stream
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, RecordDeserializer, consumerResource}

class KafkaSubscriber[F[_]: ConcurrentEffect: ContextShift: Timer, A](kafkaConfiguration: KafkaConfiguration)(
  implicit topic: KafkaTopic[A]
) extends Subscriber[F, CommittableRecord[F, *], A] {

  override def subscribe(groupId: String): Stream[F, CommittableRecord[F, A]] =
    Stream
      .resource {
        consumerResource {
          ConsumerSettings[F, Unit, A](RecordDeserializer[F, Unit], topic.deserializer[F](kafkaConfiguration))
            .withBootstrapServers(kafkaConfiguration.bootstrapServers)
            .withAutoOffsetReset(AutoOffsetReset.Latest)
            .withGroupId(groupId)
        }
      }
      .evalTap(_.subscribeTo(topic.name))
      .flatMap {
        _.stream.map { committableConsumerRecord =>
          CommittableRecord(committableConsumerRecord.record.value, committableConsumerRecord.offset.commit)
        }
      }
}

object KafkaSubscriber {
  case class CommittableRecord[F[_], A](value: A, commit: F[Unit])
}
