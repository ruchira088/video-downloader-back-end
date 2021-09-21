package com.ruchij.core.test

import cats.effect.{Async, Blocker, ContextShift, Resource, Sync}
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.types.RandomGenerator
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO

import java.util.UUID
import scala.concurrent.ExecutionContext

object DoobieProvider {
  val HashedAdminPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO." // The password is "top-secret"

  def h2InMemoryDatabaseConfiguration(databaseName: String): DatabaseConfiguration =
    DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

  def uniqueInMemoryDbConfig[F[+ _]: Sync]: F[DatabaseConfiguration] =
    RandomGenerator[F, UUID].generate
      .map(uuid => h2InMemoryDatabaseConfiguration(uuid.toString.take(8)))

  def inMemoryTransactor[F[+ _]: Async: ContextShift](
    implicit executionContext: ExecutionContext
  ): Resource[F, ConnectionIO ~> F] =
    for {
      databaseConfiguration <- Resource.eval(uniqueInMemoryDbConfig[F])
      blocker = Blocker.liftExecutionContext(executionContext)

      hikariTransactor <- DoobieTransactor.create[F](databaseConfiguration, executionContext, blocker)

      migrationResult <-
        Resource.eval {
          MigrationApp.migration(
            MigrationServiceConfiguration(databaseConfiguration, AdminConfiguration(HashedAdminPassword)),
            blocker
          )
        }
    } yield hikariTransactor.trans

}
