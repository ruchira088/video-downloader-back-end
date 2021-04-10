package com.ruchij.core.daos.doobie

import cats.effect.{Async, Blocker, ContextShift, Resource}
import cats.{Applicative, ApplicativeError}
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

object DoobieTransactor {
  val UrlName: Regex = "^jdbc:(\\w+):.*".r

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

  def parseFromConnectionUrl[F[_]: ApplicativeError[*[_], Throwable]](connectionUrl: String): F[DatabaseDriverType] =
    connectionUrl match {
      case UrlName(name) =>
        DatabaseDriverType.values
          .find(_.urlName == name)
          .map(Applicative[F].pure)
          .getOrElse {
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(
                s"""Extracted DB URL name "$name" does NOT match any DatabaseDriverTypes. Possible values: [${DatabaseDriverType.values
                  .map(_.urlName)
                  .mkString(", ")}]"""
              )
            }
          }

      case url =>
        ApplicativeError[F, Throwable].raiseError(
          new IllegalArgumentException(s"""Unable to infer driver from "$url"""")
        )
    }
}
