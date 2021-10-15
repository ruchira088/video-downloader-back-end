package com.ruchij.core.messaging.kafka

import cats.effect.{Async, Resource}
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.PubSub
import com.ruchij.core.messaging.models.CommittableRecord
import fs2.kafka.CommittableConsumerRecord

object KafkaPubSub {
  def apply[F[_]: Async, A](kafkaConfiguration: KafkaConfiguration)(
    implicit kafkaTopic: KafkaTopic[A]
  ): Resource[F, PubSub[F, CommittableRecord[CommittableConsumerRecord[F, Unit, *], *], A]] =
    for {
      publisher <- KafkaPublisher[F, A](kafkaConfiguration)
      subscriber = new KafkaSubscriber[F, A](kafkaConfiguration)
    }
    yield PubSub.from(publisher, subscriber)
}
