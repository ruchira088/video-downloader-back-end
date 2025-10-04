package com.ruchij.core.daos.doobie

import cats.effect.{Async, Resource, Sync}
import com.ruchij.core.daos.doobie.DatabaseDriver.parseFromConnectionUrl
import com.ruchij.migration.config.DatabaseConfiguration
import com.ruchij.core.types.FunctionKTypes._
import doobie.hikari.HikariTransactor

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object DoobieTransactor {

  def create[F[_]: Async](databaseConfiguration: DatabaseConfiguration): Resource[F, HikariTransactor[F]] =
    for {
      executorService <- Resource.make(Sync[F].delay(Executors.newVirtualThreadPerTaskExecutor())) {
        executorService => Sync[F].delay(executorService.shutdown())
      }

      executionContext = ExecutionContext.fromExecutorService(executorService)

      transactor <- create[F](databaseConfiguration, executionContext)
    }
    yield transactor

  def create[F[_]: Async](databaseConfiguration: DatabaseConfiguration, connectEC: ExecutionContext): Resource[F, HikariTransactor[F]] =
    Resource.eval(parseFromConnectionUrl(databaseConfiguration.url).toType[F, Throwable])
      .flatMap {
        driverType =>
          HikariTransactor.newHikariTransactor[F](
            driverType.driver,
            databaseConfiguration.url,
            databaseConfiguration.user,
            databaseConfiguration.password,
            connectEC
          )
      }
}
