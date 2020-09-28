package com.ruchij.core.daos.doobie

import cats.effect.{Async, ContextShift}
import cats.{Applicative, ApplicativeError}
import cats.implicits._
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux

import scala.util.matching.Regex

object DoobieTransactor {
  val UrlName: Regex = "^jdbc:(\\w+):.*".r

  def create[F[_]: Async: ContextShift](databaseConfiguration: DatabaseConfiguration): F[Aux[F, Unit]] =
    parseFromConnectionUrl[F](databaseConfiguration.url)
      .map { databaseDriverType =>
        Transactor.fromDriverManager(
          databaseDriverType.driver,
          databaseConfiguration.url,
          databaseConfiguration.user,
          databaseConfiguration.password
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
