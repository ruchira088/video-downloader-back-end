package com.ruchij.migration

import cats.effect.{Blocker, ContextShift, ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.ruchij.migration.config.MigrationServiceConfiguration
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.MapHasAsJava

object MigrationApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      serviceConfiguration <- IO.defer(IO.fromEither(MigrationServiceConfiguration.load(configObjectSource)))

      _ <- Blocker[IO].use { blocker =>
        migration[IO](serviceConfiguration, blocker)
      }
    } yield ExitCode.Success

  def migration[F[_]: Sync: ContextShift](migrationServiceConfiguration: MigrationServiceConfiguration, blocker: Blocker): F[MigrateResult] =
    for {
      flyway <- blocker.delay {
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

      result <- blocker.delay(flyway.migrate())
    } yield result
}
