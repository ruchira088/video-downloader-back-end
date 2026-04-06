package com.ruchij.api.config

import cats.effect.IO
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.core.config.{HttpProxyConfiguration, KafkaConfiguration, PubsubConfiguration, RedisConfiguration, SentryConfiguration, SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.messaging.PubSub.PubsubType
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

          allowed-origins = "*.localhost;*.ruchij.com"
          allowed-origins = $${?HTTP_ALLOWED_ORIGINS}
        }

        storage-configuration {
          video-folder = "./videos"
          video-folder = $${?VIDEO_FOLDER}

          image-folder = "./images"
          image-folder = $${?IMAGE_FOLDER}

          other-video-folders = "./video-folder-1;./video-folder-2"
          other-video-folders = $${?OTHER_VIDEO_FOLDERS}
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

        pubsub-configuration {
          type = "Kafka"

          kafka-configuration {
            prefix = "local"
            prefix = $${?KAFKA_PREFIX}

            bootstrap-servers = "kafka-cluster:9092"

            schema-registry = "http://kafka-cluster:8081"
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer-service:8000"
          uri = $${?SPA_SITE_RENDERER}
        }

        sentry-configuration {
          dsn = "https://key@sentry.io/123"
          environment = "test"
          traces-sample-rate = 0.5
        }
      """

    val expectedApiServiceConfiguration =
      ApiServiceConfiguration(
        HttpConfiguration(ipv4"127.0.0.1", port"80", Some(Set("*.localhost", "*.ruchij.com"))),
        StorageConfiguration("./videos", "./images", List("./video-folder-1", "./video-folder-2")),
        DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "", ""),
        RedisConfiguration("localhost", 6379, Some("redis-password")),
        AuthenticationConfiguration(30 days),
        PubsubConfiguration(PubsubType.Kafka, Some(KafkaConfiguration("local", "kafka-cluster:9092", uri"http://kafka-cluster:8081")), None, None),
        SpaSiteRendererConfiguration(uri"http://spa-renderer-service:8000"),
        SentryConfiguration(Some("https://key@sentry.io/123"), "test", 0.5),
        None
      )

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap {
      apiServiceConfiguration =>
        IO.delay {
          apiServiceConfiguration mustBe expectedApiServiceConfiguration
        }
    }
  }

  it should "parse with Redis pubsub configuration" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "redis-host"
            port = 6380
            password = "secret"
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.pubsubConfiguration.pubsubType mustBe PubsubType.Redis
        config.pubsubConfiguration.redisConfiguration must not be empty
        config.pubsubConfiguration.kafkaConfiguration mustBe None
        config.pubsubConfiguration.databaseConfiguration mustBe None

        val redisConfig = config.pubsubConfiguration.redisConfiguration.get
        redisConfig.hostname mustBe "redis-host"
        redisConfig.port mustBe 6380
        redisConfig.password mustBe Some("secret")
      }
    }
  }

  it should "parse with Doobie pubsub configuration" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Doobie"

          database-configuration {
            url = "jdbc:postgresql://pubsub-db:5432/pubsub"
            user = "pubsub_user"
            password = "pubsub_pass"
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.pubsubConfiguration.pubsubType mustBe PubsubType.Doobie
        config.pubsubConfiguration.databaseConfiguration must not be empty
        config.pubsubConfiguration.kafkaConfiguration mustBe None
        config.pubsubConfiguration.redisConfiguration mustBe None

        val dbConfig = config.pubsubConfiguration.databaseConfiguration.get
        dbConfig.url mustBe "jdbc:postgresql://pubsub-db:5432/pubsub"
        dbConfig.user mustBe "pubsub_user"
        dbConfig.password mustBe "pubsub_pass"
      }
    }
  }

  it should "fail to parse invalid configuration" in runIO {
    val invalidConfig = """invalid = "config""""

    ApiServiceConfiguration.parse[IO](ConfigSource.string(invalidConfig)).attempt.flatMap { result =>
      IO.delay {
        result.isLeft mustBe true
        result.left.exists(_.getMessage.contains("ApiServiceConfiguration")) mustBe true
      }
    }
  }

  it should "fail when required fields are missing" in runIO {
    val incompleteConfig = """
      http-configuration {
        host = "127.0.0.1"
        port = 80
      }
    """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(incompleteConfig)).attempt.flatMap { result =>
      IO.delay {
        result.isLeft mustBe true
      }
    }
  }

  it should "parse with http-proxy-configuration present" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "localhost"
            port = 6379
            password = ""
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }

        http-proxy-configuration {
          proxy-url = "http://forward-proxy:3128"
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.httpProxyConfiguration mustBe Some(HttpProxyConfiguration(uri"http://forward-proxy:3128"))
        config.httpProxyConfiguration.get.proxyUrl.host.map(_.renderString) mustBe Some("forward-proxy")
        config.httpProxyConfiguration.get.proxyUrl.port mustBe Some(3128)
      }
    }
  }

  it should "parse with http-proxy-configuration absent as None" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "localhost"
            port = 6379
            password = ""
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.httpProxyConfiguration mustBe None
      }
    }
  }

  it should "parse with http-proxy-configuration using HTTPS proxy URL" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "localhost"
            port = 6379
            password = ""
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }

        http-proxy-configuration {
          proxy-url = "https://secure-proxy.corp.net:8443"
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.httpProxyConfiguration mustBe Some(HttpProxyConfiguration(uri"https://secure-proxy.corp.net:8443"))
      }
    }
  }

  it should "fail to parse when http-proxy-configuration block is present but proxy-url is missing" in runIO {
    val configSource =
      s"""
        http-configuration {
          host = "127.0.0.1"
          port = 80
          allowed-origins = "*.localhost"
        }

        storage-configuration {
          video-folder = "./videos"
          image-folder = "./images"
          other-video-folders = ""
        }

        database-configuration {
          url = "jdbc:h2:mem:test"
          user = ""
          password = ""
        }

        redis-configuration {
          hostname = "localhost"
          port = 6379
          password = ""
        }

        authentication-configuration {
          session-duration = "30 days"
        }

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "localhost"
            port = 6379
            password = ""
          }
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer:8000"
        }

        sentry-configuration {
          environment = "test"
          traces-sample-rate = 1.0
        }

        http-proxy-configuration {
        }
      """

    ApiServiceConfiguration.parse[IO](ConfigSource.string(configSource)).attempt.flatMap { result =>
      IO.delay {
        result.isLeft mustBe true
        result.left.exists(_.getMessage.contains("proxy-url")) mustBe true
      }
    }
  }

}
