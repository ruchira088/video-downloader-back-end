package com.ruchij.core.messaging

import cats.effect.IO
import com.ruchij.core.config.PubsubConfiguration
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

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

  "PubSub.apply" should "raise ExternalServiceException when Kafka configuration is missing" in runIO {
    val config = PubsubConfiguration(PubsubType.Kafka, None, None, None)

    PubSub[IO, TestMessage](config)
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

    PubSub[IO, TestMessage](config)
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

    PubSub[IO, TestMessage](config)
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
}
