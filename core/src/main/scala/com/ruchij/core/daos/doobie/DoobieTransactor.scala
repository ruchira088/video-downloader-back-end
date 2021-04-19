package com.ruchij.core.daos.doobie

import cats.effect.{Async, Blocker, ContextShift, Resource}
import com.ruchij.core.daos.doobie.DatabaseDriverType.parseFromConnectionUrl
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

import scala.concurrent.ExecutionContext

object DoobieTransactor {

  def create[F[_]: Async: ContextShift](databaseConfiguration: DatabaseConfiguration): Resource[F, HikariTransactor[F]] =
    for {
      connectEC <- ExecutionContexts.fixedThreadPool(8)
      blocker <- Blocker[F]

      transactor <- create[F](databaseConfiguration, connectEC, blocker)
    }
    yield transactor

  def create[F[_]: Async: ContextShift](databaseConfiguration: DatabaseConfiguration, connectEC: ExecutionContext, blocker: Blocker): Resource[F, HikariTransactor[F]] =
    Resource.eval(parseFromConnectionUrl[F](databaseConfiguration.url))
      .flatMap {
        driverType =>
          HikariTransactor.newHikariTransactor[F](
            driverType.driver,
            databaseConfiguration.url,
            databaseConfiguration.user,
            databaseConfiguration.password,
            connectEC,
            blocker
          )
      }
}
