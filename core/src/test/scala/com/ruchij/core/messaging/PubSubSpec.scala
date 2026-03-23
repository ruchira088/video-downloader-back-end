package com.ruchij.core.messaging

import cats.effect.IO
import com.ruchij.core.config.PubSubConfiguration
import com.ruchij.core.daos.messaging.DoobieMessageDao
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.messaging.PubSub.PubSubType
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import vulcan.Codec
import vulcan.generic._

class PubSubSpec extends AnyFlatSpec with Matchers {

  private implicit val testMessageKafkaTopic: MessagingTopic[TestMessage] =
    new MessagingTopic[TestMessage] {
      override val name: String = "test-message-topic"
      override val avroCodec: Codec[TestMessage] = Codec.derive[TestMessage]
    }

  "PubSubType" should "contain Kafka, Redis, and Doobie values" in {
    PubSubType.values must contain allOf (PubSubType.Kafka, PubSubType.Redis, PubSubType.Doobie)
    PubSubType.values must have length 3
  }

  it should "resolve by name" in {
    PubSubType.withName("Kafka") mustBe PubSubType.Kafka
    PubSubType.withName("Redis") mustBe PubSubType.Redis
    PubSubType.withName("Doobie") mustBe PubSubType.Doobie
  }

  it should "resolve case-insensitively" in {
    PubSubType.withNameInsensitive("kafka") mustBe PubSubType.Kafka
    PubSubType.withNameInsensitive("redis") mustBe PubSubType.Redis
    PubSubType.withNameInsensitive("doobie") mustBe PubSubType.Doobie
  }

  it should "throw NoSuchMember for invalid names" in {
    assertThrows[NoSuchElementException] {
      PubSubType.withName("Invalid")
    }
  }

  "PubSub.apply" should "raise ExternalServiceException when Kafka configuration is missing" in runIO {
    val config = PubSubConfiguration(PubSubType.Kafka, None, None, None)

    PubSub[IO, TestMessage](config, DoobieMessageDao)
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
    val config = PubSubConfiguration(PubSubType.Redis, None, None, None)

    PubSub[IO, TestMessage](config, DoobieMessageDao)
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
    val config = PubSubConfiguration(PubSubType.Doobie, None, None, None)

    PubSub[IO, TestMessage](config, DoobieMessageDao)
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
