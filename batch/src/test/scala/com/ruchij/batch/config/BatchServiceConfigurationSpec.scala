package com.ruchij.batch.config

import cats.effect.IO
import com.ruchij.core.config.{HttpProxyConfiguration, KafkaConfiguration, PubsubConfiguration, RedisConfiguration, SentryConfiguration, SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class BatchServiceConfigurationSpec extends AnyFlatSpec with Matchers {

  "BatchServiceConfiguration.parse" should "parse the ConfigObjectSource" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test-suite"
          owner = $${?HOSTNAME}

          max-concurrent-downloads = 10
          max-concurrent-downloads = $${?MAX_CONCURRENT_DOWNLOADS}

          start-time = "00:00"
          start-time = $${?START_TIME}

          end-time = "00:00"
          end-time = $${?END_TIME}
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
          url = $${?DATABASE_URL}

          user = "my-user"
          user = $${?DATABASE_USER}

          password = "my-password"
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
          dsn = "https://key@sentry.io/456"
          environment = "test"
          traces-sample-rate = 0.5
        }
      """

    val expectedBatchServiceConfiguration =
      BatchServiceConfiguration(
        StorageConfiguration("./videos", "./images", List("./video-folder-1", "./video-folder-2")),
        WorkerConfiguration(10, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT, "test-suite"),
        DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "my-user", "my-password"),
        PubsubConfiguration(PubsubType.Kafka, Some(KafkaConfiguration("local", "kafka-cluster:9092", uri"http://kafka-cluster:8081")), None, None),
        RedisConfiguration("localhost", 6379, Some("redis-password")),
        SpaSiteRendererConfiguration(uri"http://spa-renderer-service:8000"),
        SentryConfiguration(Some("https://key@sentry.io/456"), "test", 0.5),
        None
      )

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap {
      batchServiceConfiguration =>
        IO.delay {
          batchServiceConfiguration mustBe expectedBatchServiceConfiguration
        }
    }
  }

  it should "parse with Redis pubsub configuration" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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

        pubsub-configuration {
          type = "Redis"

          redis-configuration {
            hostname = "redis-pubsub"
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

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.pubsubConfiguration.pubsubType mustBe PubsubType.Redis
        config.pubsubConfiguration.redisConfiguration must not be empty
        config.pubsubConfiguration.kafkaConfiguration mustBe None
        config.pubsubConfiguration.databaseConfiguration mustBe None

        val redisConfig = config.pubsubConfiguration.redisConfiguration.get
        redisConfig.hostname mustBe "redis-pubsub"
        redisConfig.port mustBe 6380
      }
    }
  }

  it should "parse with Doobie pubsub configuration" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
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

  it should "fail to parse invalid config" in runIO {
    val invalidConfig = """
      invalid = "config"
    """

    BatchServiceConfiguration.parse[IO](ConfigSource.string(invalidConfig))
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
        }
      }
  }

  it should "handle missing required fields" in runIO {
    val incompleteConfig = """
      worker-configuration {
        max-concurrent-downloads = 10
      }
    """

    BatchServiceConfiguration.parse[IO](ConfigSource.string(incompleteConfig))
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
        }
      }
  }

  it should "parse with http-proxy-configuration present" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
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
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.httpProxyConfiguration mustBe None
      }
    }
  }

  it should "parse with http-proxy-configuration using IP address proxy" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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
          proxy-url = "http://10.0.0.50:8080"
        }
      """

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { config =>
      IO.delay {
        config.httpProxyConfiguration mustBe Some(HttpProxyConfiguration(uri"http://10.0.0.50:8080"))
        config.httpProxyConfiguration.get.proxyUrl.port mustBe Some(8080)
      }
    }
  }

  it should "fail to parse when http-proxy-configuration block is present but proxy-url is missing" in runIO {
    val configSource =
      s"""
        worker-configuration {
          owner = "test"
          max-concurrent-downloads = 5
          start-time = "00:00"
          end-time = "00:00"
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

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).attempt.flatMap { result =>
      IO.delay {
        result.isLeft mustBe true
        result.left.exists(_.getMessage.contains("proxy-url")) mustBe true
      }
    }
  }

}
