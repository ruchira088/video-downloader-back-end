package com.ruchij.core.messaging.kafka

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.messaging.PubSub
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord

object KafkaPubSub {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, A](kafkaConfiguration: KafkaConfiguration)(
    implicit kafkaTopic: KafkaTopic[A]
  ): Resource[F, PubSub[F, CommittableRecord[F, *], A]] =
    for {
      publisher <- KafkaPublisher[F, A](kafkaConfiguration)
      subscriber = new KafkaSubscriber[F, A](kafkaConfiguration)
    }
    yield PubSub.from(publisher, subscriber)
}
