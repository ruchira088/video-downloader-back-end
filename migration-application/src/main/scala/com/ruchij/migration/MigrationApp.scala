package com.ruchij.migration

import cats.effect.{ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.eed3si9n.ruchij.migration.BuildInfo
import com.ruchij.migration.config.MigrationServiceConfiguration
import com.typesafe.scalalogging.Logger
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.MapHasAsJava

object MigrationApp extends IOApp {
  private val logger = Logger[MigrationApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.blocking(logger.info(BuildInfo.toString))

      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      serviceConfiguration <- IO.defer(IO.fromEither(MigrationServiceConfiguration.load(configObjectSource)))

      _ <- migration[IO](serviceConfiguration)
    } yield ExitCode.Success

  def migration[F[_]: Sync](migrationServiceConfiguration: MigrationServiceConfiguration): F[MigrateResult] =
    for {
      flyway <- Sync[F].blocking {
        Flyway
          .configure()
          .dataSource(
            migrationServiceConfiguration.databaseConfiguration.url,
            migrationServiceConfiguration.databaseConfiguration.user,
            migrationServiceConfiguration.databaseConfiguration.password
          )
          .placeholders(
            Map("adminHashedPassword" -> migrationServiceConfiguration.adminConfiguration.hashedAdminPassword)
              .asJava
          )
          .load()
      }

      result <- Sync[F].blocking(flyway.migrate())
    } yield result
}
