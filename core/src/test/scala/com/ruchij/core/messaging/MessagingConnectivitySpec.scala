package com.ruchij.core.messaging

import cats.effect.IO
import com.redis.testcontainers.{RedisContainer => RedisTestContainer}
import com.ruchij.core.config.{KafkaConfiguration, PubsubConfiguration, RedisConfiguration}
import com.ruchij.core.external.containers.{ContainerCoreResourcesProvider, RedisContainer}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.Uri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration._

class MessagingConnectivitySpec extends AnyFlatSpec with Matchers {
  "Messaging connectivity" should "use the configured messaging database without requiring migrated tables" in runIO {
    new EmbeddedCoreResourcesProvider[IO].databaseConfiguration
      .flatMap { config =>
        PubSub.provider[IO](PubsubConfiguration(PubsubType.Doobie, None, None, Some(config)))
      }
      .flatMap(_.connectivityCheck)
      .use(check => (check *> check).timeout(5.seconds))
  }

  it should "ping Redis and fail when the messaging Redis server stops" in runIO {
    ContainerCoreResourcesProvider.start[IO, RedisTestContainer](new RedisContainer()).use { container =>
      val config = RedisConfiguration(container.getHost, container.getRedisPort, None)
      PubSub.provider[IO](PubsubConfiguration(PubsubType.Redis, None, Some(config), None))
        .flatMap(_.connectivityCheck)
        .use { check =>
          for {
            _ <- check.timeout(5.seconds)
            _ <- IO.blocking(container.stop())
            result <- check.timeout(2.seconds).attempt
            _ <- IO(result.isLeft mustBe true)
          } yield ()
        }
    }
  }

  it should "query Kafka metadata without Schema Registry and fail when the broker stops" in runIO {
    ContainerCoreResourcesProvider.start[IO, ConfluentKafkaContainer] {
      new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:8.3.1"))
    }.use { container =>
      // The registry is deliberately absent: this check tests broker connectivity without serializing messages.
      val config =
        KafkaConfiguration("health", container.getBootstrapServers, Uri.unsafeFromString("http://localhost:1"))
      PubSub.provider[IO](PubsubConfiguration(PubsubType.Kafka, Some(config), None, None))
        .flatMap(_.connectivityCheck)
        .use { check =>
          for {
            _ <- check.timeout(5.seconds)
            _ <- IO.blocking(container.stop())
            result <- check.timeout(3.seconds).attempt
            _ <- IO(result.isLeft mustBe true)
          } yield ()
        }
    }
  }
}
