package com.ruchij.migration

import cats.effect.{Blocker, ContextShift, ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.ruchij.config.DatabaseConfiguration
import org.flywaydb.core.Flyway
import pureconfig.ConfigSource

object MigrationApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      databaseConfiguration <- IO.suspend(IO.fromEither(DatabaseConfiguration.load(configObjectSource)))

      _ <- Blocker[IO].use {
        blocker => migration[IO](databaseConfiguration, blocker)
      }
    }
    yield ExitCode.Success

  def migration[F[_]: Sync: ContextShift](databaseConfiguration: DatabaseConfiguration, blocker: Blocker): F[Int] =
    for {
      flyway <-
        blocker.delay {
          Flyway
            .configure()
            .dataSource(databaseConfiguration.url, databaseConfiguration.user, databaseConfiguration.password)
            .load()
        }

      result <- blocker.delay(flyway.migrate())
    } yield result
}
