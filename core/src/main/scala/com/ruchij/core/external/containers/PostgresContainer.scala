package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.migration.config.DatabaseConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresContainer {
  def create[F[_]: Sync]: Resource[F, DatabaseConfiguration] =
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
            DatabaseConfiguration(jdbcUrl, postgresContainer.getUsername, postgresContainer.getPassword)
          }
      }
}
