package com.ruchij.core.messaging.kafka

import cats.effect.{Async, Resource}
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.{MessagingTopic, PubSub}

object KafkaPubSub {
  def apply[F[_]: Async, A](kafkaConfiguration: KafkaConfiguration)(
    implicit kafkaTopic: MessagingTopic[A]
  ): Resource[F, PubSub[F, A]] =
    for {
      publisher <- KafkaPublisher[F, A](kafkaConfiguration)
      subscriber = new KafkaSubscriber[F, A](kafkaConfiguration)
    }
    yield PubSub.from(publisher, subscriber)
}
