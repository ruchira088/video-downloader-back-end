package com.ruchij.daos.doobie

import cats.effect.{Async, Blocker, ContextShift}
import cats.implicits._
import cats.{Applicative, MonadError}
import com.ruchij.config.DatabaseConfiguration
import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import org.flywaydb.core.internal.jdbc.DriverDataSource.DriverType

object DoobieTransactor {
  def create[F[_]: Async: ContextShift](
    databaseConfiguration: DatabaseConfiguration,
    blocker: Blocker
  ): F[Aux[F, Unit]] =
    driverType[F](databaseConfiguration.url)
      .map { driver =>
        Transactor.fromDriverManager(
          driver.driverClass,
          databaseConfiguration.url,
          databaseConfiguration.user,
          databaseConfiguration.password
        )
      }

  def driverType[F[_]: MonadError[*[_], Throwable]](url: String): F[DriverType] =
    DriverType
      .values()
      .find { driver =>
        url.toLowerCase.startsWith(driver.prefix)
      }
      .fold[F[DriverType]](
        MonadError[F, Throwable].raiseError(new IllegalArgumentException(s"""Unable to infer driver from "$url""""))
      )(Applicative[F].pure)
}
