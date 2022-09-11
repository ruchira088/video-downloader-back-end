package com.ruchij.migration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.migration.MigrationAppSpec.{AdminConfig, DatabaseConfig}
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class MigrationAppSpec extends AnyFlatSpec with Matchers {
  "migration" should "run without any errors" in {
    MigrationApp.migration[IO](MigrationServiceConfiguration(DatabaseConfig, AdminConfig))
      .flatMap { migrationResult =>
        IO.delay { migrationResult.success mustBe true }
      }
      .unsafeRunSync()
  }
}

object MigrationAppSpec {
  val HashedAdminPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO." // The password is "top-secret"

  val AdminConfig: AdminConfiguration = AdminConfiguration(HashedAdminPassword)

  val DatabaseConfig: DatabaseConfiguration =
    DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-migration-application;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )
}
