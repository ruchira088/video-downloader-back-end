package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.migration.config.DatabaseConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer

import java.sql.DriverManager
import scala.util.Using

object PostgresContainer {
  // The PostgreSQL driver waits forever by default. A container whose mapped port accepts the connection but never
  // answers the startup handshake would otherwise hang the test run, so every connection gets bounded timeouts.
  private val ConnectionTimeouts = "loginTimeout=15&connectTimeout=10&socketTimeout=120"

  private val ReadinessAttempts = 3

  // Once a container's mapped port stops answering, retrying it doesn't help, so a fresh container is started instead
  private val ContainerAttempts = 3

  def create[F[_]: Sync]: Resource[F, DatabaseConfiguration] = readyContainer(ContainerAttempts)

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
