package com.ruchij.migration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.migration.MigrationAppSpec.{AdminConfig, DatabaseConfig}
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class MigrationAppSpec extends AnyFlatSpec with Matchers {
  "migration" should "run without any errors" in {
    MigrationApp.migration[IO](MigrationServiceConfiguration(DatabaseConfig, AdminConfig))
      .flatMap { migrationResult =>
        IO.delay { migrationResult.success mustBe true }
      }
      .unsafeRunSync()
  }

  it should "apply migrations to a fresh database" in {
    val freshDbConfig = DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-fresh-${System.currentTimeMillis()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )
    val config = MigrationServiceConfiguration(freshDbConfig, AdminConfig)

    MigrationApp.migration[IO](config)
      .flatMap { migrationResult =>
        IO.delay {
          migrationResult.success mustBe true
          migrationResult.migrationsExecuted must be >= 1
        }
      }
      .unsafeRunSync()
  }

  it should "be idempotent when run multiple times" in {
    val idempotentDbConfig = DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-idempotent-${System.currentTimeMillis()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )
    val config = MigrationServiceConfiguration(idempotentDbConfig, AdminConfig)

    val result = for {
      firstRun <- MigrationApp.migration[IO](config)
      secondRun <- MigrationApp.migration[IO](config)
    } yield (firstRun, secondRun)

    result.flatMap { case (first, second) =>
      IO.delay {
        first.success mustBe true
        second.success mustBe true
        second.migrationsExecuted mustBe 0  // No new migrations on second run
      }
    }.unsafeRunSync()
  }
}

class MigrationServiceConfigurationSpec extends AnyFlatSpec with Matchers {

  "MigrationServiceConfiguration.load" should "successfully load valid configuration" in {
    val configString =
      """
        |database-configuration {
        |  url = "jdbc:h2:mem:test;MODE=PostgreSQL"
        |  user = "testuser"
        |  password = "testpass"
        |}
        |admin-configuration {
        |  hashed-admin-password = "hashedpassword123"
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val result = MigrationServiceConfiguration.load(ConfigSource.fromConfig(config))

    result.isRight mustBe true
    result.toOption.get.databaseConfiguration.url mustBe "jdbc:h2:mem:test;MODE=PostgreSQL"
    result.toOption.get.databaseConfiguration.user mustBe "testuser"
    result.toOption.get.databaseConfiguration.password mustBe "testpass"
    result.toOption.get.adminConfiguration.hashedAdminPassword mustBe "hashedpassword123"
  }

  it should "return error for missing database configuration" in {
    val configString =
      """
        |admin-configuration {
        |  hashed-admin-password = "hashedpassword123"
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val result = MigrationServiceConfiguration.load(ConfigSource.fromConfig(config))

    result.isLeft mustBe true
  }

  it should "return error for missing admin configuration" in {
    val configString =
      """
        |database-configuration {
        |  url = "jdbc:h2:mem:test;MODE=PostgreSQL"
        |  user = "testuser"
        |  password = "testpass"
        |}
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val result = MigrationServiceConfiguration.load(ConfigSource.fromConfig(config))

    result.isLeft mustBe true
  }

  it should "return error for empty configuration" in {
    val config = ConfigFactory.empty()
    val result = MigrationServiceConfiguration.load(ConfigSource.fromConfig(config))

    result.isLeft mustBe true
  }

  it should "return error for invalid configuration format" in {
    val configString =
      """
        |database-configuration = "invalid"
        |""".stripMargin

    val config = ConfigFactory.parseString(configString)
    val result = MigrationServiceConfiguration.load(ConfigSource.fromConfig(config))

    result.isLeft mustBe true
  }
}

class DatabaseConfigurationSpec extends AnyFlatSpec with Matchers {

  "DatabaseConfiguration" should "store all fields correctly" in {
    val config = DatabaseConfiguration(
      url = "jdbc:postgresql://localhost:5432/testdb",
      user = "admin",
      password = "secret"
    )

    config.url mustBe "jdbc:postgresql://localhost:5432/testdb"
    config.user mustBe "admin"
    config.password mustBe "secret"
  }

  it should "allow empty credentials" in {
    val config = DatabaseConfiguration(
      url = "jdbc:h2:mem:test",
      user = "",
      password = ""
    )

    config.user mustBe ""
    config.password mustBe ""
  }

  it should "support H2 in-memory URLs" in {
    val config = DatabaseConfiguration(
      url = "jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      user = "",
      password = ""
    )

    config.url must include("h2:mem")
    config.url must include("MODE=PostgreSQL")
  }

  it should "support PostgreSQL URLs" in {
    val config = DatabaseConfiguration(
      url = "jdbc:postgresql://db.example.com:5432/mydb?ssl=true",
      user = "dbuser",
      password = "dbpass"
    )

    config.url must include("postgresql")
    config.url must include("ssl=true")
  }
}

class AdminConfigurationSpec extends AnyFlatSpec with Matchers {

  "AdminConfiguration" should "store hashed admin password" in {
    val hashedPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."
    val config = AdminConfiguration(hashedPassword)

    config.hashedAdminPassword mustBe hashedPassword
  }

  it should "allow any string as hashed password" in {
    val config = AdminConfiguration("any-string-value")
    config.hashedAdminPassword mustBe "any-string-value"
  }

  it should "allow empty hashed password" in {
    val config = AdminConfiguration("")
    config.hashedAdminPassword mustBe ""
  }

  it should "preserve bcrypt hash format" in {
    val bcryptHash = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWX"
    val config = AdminConfiguration(bcryptHash)

    config.hashedAdminPassword must startWith("$2a$")
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
