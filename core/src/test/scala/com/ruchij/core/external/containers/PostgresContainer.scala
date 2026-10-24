package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.DatabaseConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer

import java.sql.DriverManager
import java.util.UUID
import scala.util.Using

object PostgresContainer {
  // The PostgreSQL driver waits forever by default. A container whose mapped port accepts the connection but never
  // answers the startup handshake would otherwise hang the test run, so every connection gets bounded timeouts.
  private val ConnectionTimeouts = "loginTimeout=15&connectTimeout=10&socketTimeout=120"

  private val ReadinessAttempts = 3

  // Once a container's mapped port stops answering, retrying it doesn't help, so a fresh container is started instead
  private val ContainerAttempts = 3

  def create[F[_]: Sync]: Resource[F, DatabaseConfiguration] = readyContainer(ContainerAttempts)

  /**
    * Starts a container with a migrated database in it, and returns a resource that creates a fresh copy of that
    * database each time it is used. Copying a template database is far faster than migrating a new database, so
    * every test can get a database of its own without a container or a migration run per test.
    */
  def migratedDatabases[F[_]: Sync]: Resource[F, Resource[F, DatabaseConfiguration]] =
    create[F]
      .evalTap { templateConfiguration =>
        MigrationApp.migration[F](CoreResourcesProvider.migrationServiceConfiguration(templateConfiguration))
      }
      .map(templateConfiguration => databaseCopy[F](templateConfiguration))

  // The copy's database name only contains characters that need no quoting
  private def databaseCopy[F[_]: Sync](templateConfiguration: DatabaseConfiguration): Resource[F, DatabaseConfiguration] =
    Resource
      .eval(Sync[F].delay(s"test_${UUID.randomUUID().toString.replace("-", "")}"))
      .flatMap { copyName =>
        // PostgreSQL only copies a template that nobody is connected to, so these statements run in the maintenance
        // database. Every test of a suite runs one after the other, so no two copies are made at the same time.
        val templateName = databaseName(templateConfiguration)

        Resource.make(
          execute(templateConfiguration, s"""CREATE DATABASE $copyName TEMPLATE "$templateName"""")
            .as(templateConfiguration.copy(url = withDatabaseName(templateConfiguration.url, copyName)))
        )(_ => execute(templateConfiguration, s"DROP DATABASE IF EXISTS $copyName WITH (FORCE)"))
      }

  private val JdbcUrl = "(jdbc:postgresql://[^/]+/)([^?]+)(.*)".r

  private def databaseName(databaseConfiguration: DatabaseConfiguration): String =
    databaseConfiguration.url match {
      case JdbcUrl(_, databaseName, _) => databaseName
      case url => throw new IllegalArgumentException(s"Not a PostgreSQL JDBC URL: $url")
    }

  private def withDatabaseName(url: String, databaseName: String): String =
    url match {
      case JdbcUrl(prefix, _, parameters) => s"$prefix$databaseName$parameters"
      case _ => throw new IllegalArgumentException(s"Not a PostgreSQL JDBC URL: $url")
    }

  private def execute[F[_]: Sync](templateConfiguration: DatabaseConfiguration, sql: String): F[Unit] =
    Sync[F].blocking {
      val DatabaseConfiguration(url, user, password) = templateConfiguration

      Using.resource(DriverManager.getConnection(withDatabaseName(url, "postgres"), user, password)) { connection =>
        Using.resource(connection.createStatement())(_.execute(sql))
      }
    }.void

  private def readyContainer[F[_]: Sync](attempts: Int): Resource[F, DatabaseConfiguration] =
    container[F].attempt.flatMap {
      case Right(databaseConfiguration) => Resource.pure(databaseConfiguration)
      case Left(_) if attempts > 1 => readyContainer(attempts - 1)
      case Left(throwable) => Resource.raiseError[F, DatabaseConfiguration, Throwable](throwable)
    }

  private def container[F[_]: Sync]: Resource[F, DatabaseConfiguration] =
    Resource
      .eval {
        Sync[F].delay {
          new PostgreSQLContainer("postgres:16")
            .withUsername("my-user")
            .withPassword("my-password")
            .withDatabaseName("video-downloader")
        }
      }
      .flatMap(postgresContainer => ContainerCoreResourcesProvider.start(postgresContainer))
      .evalMap { postgresContainer =>
        Sync[F]
          .blocking(postgresContainer.getJdbcUrl)
          .map { jdbcUrl =>
            DatabaseConfiguration(
              withConnectionTimeouts(jdbcUrl),
              postgresContainer.getUsername,
              postgresContainer.getPassword
            )
          }
      }
      .evalTap(databaseConfiguration => awaitReady(databaseConfiguration, ReadinessAttempts))

  private def withConnectionTimeouts(jdbcUrl: String): String =
    if (jdbcUrl.contains("?")) s"$jdbcUrl&$ConnectionTimeouts" else s"$jdbcUrl?$ConnectionTimeouts"

  // Retries a failed handshake, since a fresh connection to the same container usually succeeds
  private def awaitReady[F[_]: Sync](databaseConfiguration: DatabaseConfiguration, attempts: Int): F[Unit] =
    Sync[F]
      .blocking {
        val DatabaseConfiguration(url, user, password) = databaseConfiguration

        Using.resource(DriverManager.getConnection(url, user, password)) { connection =>
          Using.resource(connection.createStatement())(_.execute("SELECT 1"))
        }
      }
      .void
      .handleErrorWith { throwable =>
        if (attempts > 1) Sync[F].blocking(Thread.sleep(1000)) >> awaitReady(databaseConfiguration, attempts - 1)
        else Sync[F].raiseError(throwable)
      }
}
