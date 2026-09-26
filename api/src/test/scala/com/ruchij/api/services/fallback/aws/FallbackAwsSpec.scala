package com.ruchij.api.services.fallback.aws

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.api.config.FallbackSyncSettings
import com.ruchij.api.external.containers.{DynamoDbLocalContainer, ElasticMqContainer}
import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, SyncJson}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.services.sqs.model.{CreateQueueRequest, SendMessageRequest}

import java.time.Instant
import scala.jdk.CollectionConverters._

class FallbackAwsSpec extends AnyFlatSpec with Matchers {

  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

  private def clients(endpoint: String): Resource[IO, FallbackSyncAwsClients] =
    FallbackSyncAwsClients.create[IO](
      FallbackSyncSettings("unused", "unused", "unused", "ap-southeast-2", Some(endpoint)),
      credentials
    )

  private def queueUrl(aws: FallbackSyncAwsClients, name: String): IO[String] =
    IO.fromCompletableFuture(IO(aws.sqs.createQueue(CreateQueueRequest.builder().queueName(name).build())))
      .map(_.queueUrl())

  "SqsFallbackSyncTransport" should "send every message, batching more than ten" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "main-to-fallback")
        messages = (1 to 12).toList.map(index => ScheduledVideoRemoval(s"video-$index", Instant.EPOCH))
        _ <- new SqsFallbackSyncTransport[IO](aws.sqs, url).send(messages)
        queue = new SqsFallbackRequestQueue[IO](aws.sqs, url, waitTimeSeconds = 1)
        received <- queue.receive.flatMap(first => queue.receive.map(first ++ _))
      } yield received.map(_.body).toSet mustBe messages.map(SyncJson.encode).toSet
    }
  }

  it should "fail when SQS rejects any entry of a batch" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "main-to-fallback")
        // A body over the SQS limit is rejected per entry while the batch call itself succeeds.
        oversized = ScheduledVideoRemoval("x" * (1024 * 1024 + 1), Instant.EPOCH)
        result <- new SqsFallbackSyncTransport[IO](aws.sqs, url).send(List(oversized)).attempt
      } yield result.isLeft mustBe true
    }
  }

  "SqsFallbackRequestQueue" should "report the receive count and delete handled messages" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "fallback-to-main")
        _ <- IO.fromCompletableFuture(
          IO(aws.sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody("hello").build()))
        )
        queue = new SqsFallbackRequestQueue[IO](aws.sqs, url, waitTimeSeconds = 1)
        received <- queue.receive
        _ <- received.traverse_(message => queue.delete(message.receiptHandle))
        afterDelete <- queue.receive
      } yield {
        received.map(message => (message.body, message.receiveCount)) mustBe List(("hello", 1))
        afterDelete mustBe empty
      }
    }
  }

  "DynamoDbFallbackManifestReader" should "return live video items only, across scan pages" in runIO {
    (DynamoDbLocalContainer.create[IO].flatMap(clients)).use { aws =>
      def put(item: Map[String, AttributeValue]): IO[Unit] =
        IO.fromCompletableFuture(
          IO(aws.dynamoDb.putItem(PutItemRequest.builder().tableName("videos").item(item.asJava).build()))
        ).void

      def s(value: String): AttributeValue = AttributeValue.builder().s(value).build()
      def bool(value: Boolean): AttributeValue = AttributeValue.builder().bool(value).build()

      val createTable =
        CreateTableRequest
          .builder()
          .tableName("videos")
          .billingMode(BillingMode.PAY_PER_REQUEST)
          .keySchema(
            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
          )
          .attributeDefinitions(
            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()
          )
          .build()

      for {
        _ <- IO.fromCompletableFuture(IO(aws.dynamoDb.createTable(createTable)))
        _ <- (1 to 30).toList.traverse_ { index =>
          put(
            Map(
              "PK" -> s(s"VIDEO#video-$index"),
              "SK" -> s("VIDEO"),
              "hash" -> s(s"hash-$index"),
              "capturedAt" -> s("2026-09-26T08:15:30.123Z"),
              "deleted" -> bool(false),
              "padding" -> s("p" * 40000)
            )
          )
        }
        _ <- put(
          Map(
            "PK" -> s("VIDEO#gone"),
            "SK" -> s("VIDEO"),
            "capturedAt" -> s("2026-09-26T08:15:30.123Z"),
            "deleted" -> bool(true)
          )
        )
        _ <- put(Map("PK" -> s("USER#user-1"), "SK" -> s("VIDEO#2026#video-1")))
        manifest <- new DynamoDbFallbackManifestReader[IO](aws.dynamoDb, "videos").manifest
      } yield {
        manifest.keySet mustBe (1 to 30).map(index => s"video-$index").toSet
        manifest("video-7") mustBe ManifestEntry("hash-7", Instant.parse("2026-09-26T08:15:30.123Z"))
      }
    }
  }

  "The contract fixture" should "fit in one SQS message" in {
    SyncJson.encode(fixtureUpsert).length must be < 1024 * 1024
  }
}
