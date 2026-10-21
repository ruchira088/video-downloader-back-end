package com.ruchij.core.messaging.db

import cats.effect.Async
import cats.{Monad, ~>}
import com.ruchij.core.daos.messaging.{MessageConsumerGroupDao, MessageDao}
import com.ruchij.core.messaging.{MessagingTopic, PubSub}
import com.ruchij.core.types.Clock

import scala.concurrent.duration.FiniteDuration

object DoobiePubSub {
  def apply[F[_]: Async: Clock, G[_]: Monad, A: MessagingTopic](
    messageDao: MessageDao[G],
    consumerGroupDao: MessageConsumerGroupDao[G],
    pollInterval: FiniteDuration = DoobieSubscriber.DefaultPollInterval
  )(implicit transaction: G ~> F): PubSub[F, A] = {
    val publisher = new DoobiePublisher[F, G, A](messageDao)
    val subscriber = DoobieSubscriber.create[F, G, A](consumerGroupDao, pollInterval)
    PubSub.from(publisher, subscriber)
  }
}
