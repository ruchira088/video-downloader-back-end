package com.ruchij.core.messaging.postgres

import cats.effect.{Async, Resource}
import cats.{Id, MonadThrow, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.PubSub
import com.ruchij.migration.config.DatabaseConfiguration

object PostgresPubSub {
  def apply[F[_]: Async, G[_]: MonadThrow, A: PostgresTopic](
    messageDao: MessageDao[G],
    databaseConfiguration: DatabaseConfiguration
  )(implicit transaction: G ~> F): Resource[F, PubSub[F, Id, A]] =
    PostgresSubscriber.create[F, G, A](messageDao, databaseConfiguration)
      .map { subscriber =>
        val publisher = new PostgresPublisher[F, G, A](messageDao)
        PubSub.from(publisher, subscriber)
      }
}
