package com.ruchij.core.test

import cats.effect.{Async, Blocker, ContextShift, Sync}
import cats.implicits._
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.types.RandomGenerator
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.util.transactor.Transactor

import java.util.UUID

object DoobieProvider {
  def h2InMemoryDatabaseConfiguration(databaseName: String): DatabaseConfiguration =
    DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

  def uniqueH2InMemoryDatabaseConfiguration[F[+ _]: Sync]: F[DatabaseConfiguration] =
    RandomGenerator[F, UUID].generate
      .map(_.toString)
      .map(h2InMemoryDatabaseConfiguration)

  def h2InMemoryTransactor[F[+ _]: Async: ContextShift]: F[Transactor.Aux[F, Unit]] =
    Blocker[F].use {
      blocker =>
        uniqueH2InMemoryDatabaseConfiguration[F]
          .flatTap(dbConfig => MigrationApp.migration(dbConfig, blocker))
          .flatMap(DoobieTransactor.create[F])
    }
}
