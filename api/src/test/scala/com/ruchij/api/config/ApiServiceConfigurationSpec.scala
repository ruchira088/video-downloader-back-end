package com.ruchij.api.config

import cats.effect.IO
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax
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
          session-duration = "30 days"
          session-duration = $${?SESSION_DURATION}
        }

        kafka-configuration {
          prefix = "local"
          prefix = $${?KAFKA_PREFIX}

          bootstrap-servers = "kafka-cluster:9092"

          schema-registry = "http://kafka-cluster:8081"
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer-service:8000"
          uri = $${?SPA_SITE_RENDERER}
        }

        fallback-api-configuration {
          uri = "https://fallback-api.video.dev.ruchij.com"
          bearer-token = my-token
          poll-interval = "5 minutes"
        }
      """

    val expectedApiServiceConfiguration =
      ApiServiceConfiguration(
        HttpConfiguration(ipv4"127.0.0.1", port"80"),
        ApiStorageConfiguration("./images"),
        DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "", ""),
        RedisConfiguration("localhost", 6379, Some("redis-password")),
        AuthenticationConfiguration(30 days),
        KafkaConfiguration("local", "kafka-cluster:9092", uri"http://kafka-cluster:8081"),
        SpaSiteRendererConfiguration(uri"http://spa-renderer-service:8000"),
        FallbackApiConfiguration(uri"https://fallback-api.video.dev.ruchij.com", "my-token", 5 minutes)
      )

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap {
      apiServiceConfiguration =>
        IO.delay {
          apiServiceConfiguration mustBe expectedApiServiceConfiguration
        }
    }
  }

}
