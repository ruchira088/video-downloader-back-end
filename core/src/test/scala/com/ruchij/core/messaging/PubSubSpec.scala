package com.ruchij.core.messaging

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.core.config.PubsubConfiguration
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.MigrationApp
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class PubSubSpec extends AnyFlatSpec with Matchers {

  "PubSubType" should "contain Kafka, Redis, and Doobie values" in {
    PubsubType.values must contain allOf (PubsubType.Kafka, PubsubType.Redis, PubsubType.Doobie)
    PubsubType.values must have length 3
  }

  it should "resolve by name" in {
    PubsubType.withName("Kafka") mustBe PubsubType.Kafka
    PubsubType.withName("Redis") mustBe PubsubType.Redis
    PubsubType.withName("Doobie") mustBe PubsubType.Doobie
  }

  it should "resolve case-insensitively" in {
    PubsubType.withNameInsensitive("kafka") mustBe PubsubType.Kafka
    PubsubType.withNameInsensitive("redis") mustBe PubsubType.Redis
    PubsubType.withNameInsensitive("doobie") mustBe PubsubType.Doobie
  }

  it should "throw NoSuchMember for invalid names" in {
    assertThrows[NoSuchElementException] {
      PubsubType.withName("Invalid")
    }
  }

  "PubSub.provider" should "raise ExternalServiceException when Kafka configuration is missing" in runIO {
    val config = PubsubConfiguration(PubsubType.Kafka, None, None, None)

    PubSub
      .provider[IO](config)
      .use(_ => IO.unit)
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.toOption.get mustBe a[ExternalServiceException]
          result.left.toOption.get.getMessage must include("kafka-configuration is empty")
        }
      }
  }

  it should "raise ExternalServiceException when Redis configuration is missing" in runIO {
    val config = PubsubConfiguration(PubsubType.Redis, None, None, None)

    PubSub
      .provider[IO](config)
      .use(_ => IO.unit)
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.toOption.get mustBe a[ExternalServiceException]
          result.left.toOption.get.getMessage must include("redis-configuration is empty")
        }
      }
  }

  it should "raise ExternalServiceException when Doobie database configuration is missing" in runIO {
    val config = PubsubConfiguration(PubsubType.Doobie, None, None, None)

    PubSub
      .provider[IO](config)
      .use(_ => IO.unit)
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.toOption.get mustBe a[ExternalServiceException]
          result.left.toOption.get.getMessage must include("database-configuration is empty")
        }
      }
  }

  it should "share a single transactor between Doobie-backed topics" in runIO {
    new EmbeddedCoreResourcesProvider[IO].databaseConfiguration
      .flatMap { databaseConfiguration =>
        Resource.eval(MigrationApp.migration[IO](CoreResourcesProvider.migrationServiceConfiguration(databaseConfiguration)))
          .productR(PubSub.provider[IO](PubsubConfiguration(PubsubType.Doobie, None, None, Some(databaseConfiguration))))
      }
      .use { provider =>
        provider.pubSub[TestMessage].product(provider.pubSub[TestMessage]).use {
          case (first, second) =>
            val message = TestMessage("shared-transactor", 1)

            second
              .subscribe("group")
              .take(1)
              .concurrently(Stream.sleep[IO](200.millis) >> Stream.eval(first.publishOne(message)))
              .compile
              .lastOrError
              .map { received =>
                received mustBe message
                provider.messageTransaction mustBe defined
              }
        }
      }
  }
}
