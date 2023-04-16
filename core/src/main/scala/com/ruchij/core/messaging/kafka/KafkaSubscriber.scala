package com.ruchij.core.messaging.kafka

import cats.effect.Async
import cats.implicits.toFunctorOps
import cats.{Foldable, Functor}
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.CommittableRecord
import fs2.Stream
import fs2.kafka._

class KafkaSubscriber[F[_]: Async, A](kafkaConfiguration: KafkaConfiguration)(
  implicit topic: KafkaTopic[A]
) extends Subscriber[F, CommittableRecord[CommittableConsumerRecord[F, Unit, *], *], A] {

  private val logger = Logger[KafkaSubscriber[F, A]]

  override def subscribe(groupId: String): Stream[F, CommittableRecord[CommittableConsumerRecord[F, Unit, *], A]] =
    Stream
      .resource {
        KafkaConsumer.resource {
          ConsumerSettings[F, Unit, A](GenericDeserializer[F, Unit], topic.deserializer[F](kafkaConfiguration))
            .withBootstrapServers(kafkaConfiguration.bootstrapServers)
            .withAutoOffsetReset(AutoOffsetReset.Latest)
            .withGroupId(groupId)
        }
      }
      .evalTap(_.subscribeTo(topic.name))
      .flatMap {
        _.stream.evalMap { committableConsumerRecord =>
          logger.trace[F](s"Received: topic=${committableConsumerRecord.record.topic}, consumerGroupId=${committableConsumerRecord.offset.consumerGroupId}, value=${committableConsumerRecord.record.value}")
            .as {
              CommittableRecord(committableConsumerRecord.record.value, committableConsumerRecord)
            }
        }
      }

  override def commit[H[_]: Foldable: Functor](values: H[CommittableRecord[CommittableConsumerRecord[F, Unit, *], A]]): F[Unit] =
    CommittableOffsetBatch.fromFoldable(values.map(_.raw.offset)).commit
}