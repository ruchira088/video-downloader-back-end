package com.ruchij.core.messaging.postgres

import cats.Id
import cats.effect.{Async, Resource}
import com.ruchij.core.messaging.PubSub
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.Transactor

object PostgresPubSub {
  def apply[F[_]: Async, A: PostgresTopic](
    databaseConfiguration: DatabaseConfiguration,
    transactor: Transactor[F]
  ): Resource[F, PubSub[F, Id, A]] =
    PostgresSubscriber.create[F, A](databaseConfiguration, transactor)
      .map { subscriber =>
        val publisher = PostgresPublisher.create[F, A](transactor)
        PubSub.from(publisher, subscriber)
      }
}
