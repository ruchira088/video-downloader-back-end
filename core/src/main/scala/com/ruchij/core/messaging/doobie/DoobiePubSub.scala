package com.ruchij.core.messaging.doobie

import cats.effect.Async
import cats.{Id, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.PubSub
import com.ruchij.core.types.Clock

import scala.concurrent.duration.FiniteDuration

object DoobiePubSub {
  def apply[F[_]: Async: Clock, G[_], A: DoobieTopic](
    messageDao: MessageDao[G],
    pollInterval: FiniteDuration = DoobieSubscriber.DefaultPollInterval
  )(implicit transaction: G ~> F): PubSub[F, Id, A] = {
    val publisher = new DoobiePublisher[F, G, A](messageDao)
    val subscriber = DoobieSubscriber.create[F, G, A](messageDao, pollInterval)
    PubSub.from(publisher, subscriber)
  }
}
