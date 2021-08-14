package com.ruchij.api.config

import cats.effect.IO
import com.ruchij.api.config.AuthenticationConfiguration.{HashedPassword, PasswordAuthenticationConfiguration}
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class ApiServiceConfigurationSpec extends AnyFlatSpec with Matchers {

  "ApiServiceConfiguration.parse" should "parse the ConfigObjectSource" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"

          port = 80
        }

        storage-configuration {
          image-folder = "./images"
        }

        database-configuration {
          url = "jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"

          user = ""
          user = $${?DATABASE_USER}

          password = ""
          password = $${?DATABASE_PASSWORD}
        }

        redis-configuration {
          hostname = "localhost"
          hostname = $${?REDIS_HOSTNAME}

          port = 6379
          port = $${?REDIS_PORT}

          password = "redis-password"
          password = $${?REDIS_PASSWORD}
        }

        authentication-configuration {
          // The password is "top-secret"
          hashed-password = "$$2a$$10$$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."
          hashed-password = $${?API_HASHED_PASSWORD}

          session-duration = "30 days"
          session-duration = $${?SESSION_DURATION}
        }

        kafka-configuration {
          bootstrap-servers = "kafka-cluster:9092"

          schema-registry = "http://kafka-cluster:8081"
        }

        application-information {
          instance-id = "localhost"

          git-branch = "my-branch"
          git-commit = "my-commit"
          build-timestamp = "2021-08-06T01:20:00.000Z"
        }
      """

    val expectedApiServiceConfiguration =
      ApiServiceConfiguration(
        HttpConfiguration("127.0.0.1", 80),
        ApiStorageConfiguration("./images"),
        DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "", ""),
        RedisConfiguration("localhost", 6379, Some("redis-password")),
        PasswordAuthenticationConfiguration(HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), 30 days),
        KafkaConfiguration("kafka-cluster:9092", uri"http://kafka-cluster:8081"),
        ApplicationInformation("localhost", Some("my-branch"), Some("my-commit"), Some(new DateTime(2021, 8, 6, 1, 20, 0, 0, DateTimeZone.UTC)))
      )

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap {
      apiServiceConfiguration =>
        IO.delay {
          apiServiceConfiguration mustBe expectedApiServiceConfiguration
        }
    }
  }

}
