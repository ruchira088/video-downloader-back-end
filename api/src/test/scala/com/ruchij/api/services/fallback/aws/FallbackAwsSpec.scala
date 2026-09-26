package com.ruchij.api.services.fallback.aws

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.api.config.FallbackSyncSettings
import com.ruchij.api.external.containers.{DynamoDbLocalContainer, ElasticMqContainer}
import com.ruchij.api.services.fallback.ContractFixtures
import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, SyncJson}
import com.ruchij.core.test.IOSupport.runIO
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  BatchRequestTooLongException,
  BatchResultErrorEntry,
  CreateQueueRequest,
  SendMessageBatchRequest,
  SendMessageBatchResponse,
  SendMessageBatchResultEntry,
  SendMessageRequest,
  SqsException
}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration._
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
        queue = new SqsFallbackRequestQueue[IO](aws.sqs, url, waitTimeSeconds = 1, maxNumberOfMessages = 10)
        received <- queue.receive.flatMap(first => queue.receive.map(first ++ _))
      } yield received.map(_.body).toSet mustBe messages.map(SyncJson.encode).toSet
    }
  }

  /** A stubbed client, because ElasticMQ fails the whole call for the bodies a test can produce, so it can't return
    * the successful-but-partially-failed responses these tests need. `respond` answers each call, given its number
    * (from 1) and the request; the client records every request it receives. */
  private final class StubSqsClient(respond: (Int, SendMessageBatchRequest) => SendMessageBatchResponse)
      extends SqsAsyncClient {
    private val received = new java.util.concurrent.ConcurrentLinkedQueue[SendMessageBatchRequest]()

    def requests: List[SendMessageBatchRequest] = received.asScala.toList

    override def serviceName(): String = SqsAsyncClient.SERVICE_NAME

    override def close(): Unit = ()

    override def sendMessageBatch(request: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
      received.add(request)
      try CompletableFuture.completedFuture(respond(received.size(), request))
      catch { case error: Throwable => CompletableFuture.failedFuture(error) }
    }
  }

  private def batchResponse(request: SendMessageBatchRequest, failed: Map[String, Boolean]): SendMessageBatchResponse =
    SendMessageBatchResponse
      .builder()
      .successful(
        request.entries().asScala.filterNot(entry => failed.contains(entry.id())).map { entry =>
          SendMessageBatchResultEntry.builder().id(entry.id()).messageId(s"message-${entry.id()}").build()
        }.asJava
      )
      .failed(
        failed.toList.map {
          case (id, senderFault) =>
            BatchResultErrorEntry.builder().id(id).code("SomeError").senderFault(senderFault).message("boom").build()
        }.asJava
      )
      .build()

  private val noDelays = List(Duration.Zero, Duration.Zero)

  private def removals(count: Int): List[ScheduledVideoRemoval] =
    (1 to count).toList.map(index => ScheduledVideoRemoval(s"video-$index", Instant.EPOCH))

  private def videoIds(request: SendMessageBatchRequest): List[String] =
    request.entries().asScala.toList.map(entry => entry.messageBody()).flatMap { body =>
      io.circe.parser.parse(body).toOption.flatMap(_.hcursor.get[String]("videoId").toOption)
    }

  it should "retry a transiently failed entry, then fail naming it" in runIO {
    val sqs = new StubSqsClient((_, request) => batchResponse(request, Map("1" -> false)))

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(2)).attempt.map { result =>
      result.left.map(_.getMessage) mustBe
        Left("SQS rejected 1 of 2 messages: ScheduledVideoRemoval for video video-2: SomeError boom")
      // The first attempt sends both entries; each retry resends only the failed one
      sqs.requests.map(videoIds) mustBe List(List("video-1", "video-2"), List("video-2"), List("video-2"))
    }
  }

  it should "succeed when a transient failure clears on a retry" in runIO {
    val sqs =
      new StubSqsClient((call, request) => batchResponse(request, if (call == 1) Map("0" -> false) else Map.empty))

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(2)).attempt.map { result =>
      result mustBe Right(())
      sqs.requests.map(videoIds) mustBe List(List("video-1", "video-2"), List("video-1"))
    }
  }

  it should "drop an entry SQS rejects as the sender's fault without retrying or failing" in runIO {
    val sqs = new StubSqsClient((_, request) => batchResponse(request, Map("0" -> true)))

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(2)).attempt.map { result =>
      result mustBe Right(())
      sqs.requests.size mustBe 1
    }
  }

  it should "send every later batch when an earlier batch's call fails, then fail" in runIO {
    val sqs =
      new StubSqsClient({ (_, request) =>
        if (videoIds(request).contains("video-1")) throw new RuntimeException("connection reset")
        else batchResponse(request, Map.empty)
      })

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(12)).attempt.map { result =>
      result.left.map(_.getMessage.startsWith("SQS rejected 10 of 12 messages")) mustBe Left(true)
      sqs.requests.map(videoIds).count(_ == List("video-11", "video-12")) mustBe 1
      sqs.requests.size mustBe 4
    }
  }

  private def sqsError(statusCode: Int, errorCode: String): SqsException =
    SqsException
      .builder()
      .statusCode(statusCode)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage("boom").build())
      .build()
      .asInstanceOf[SqsException]

  it should "send each message of a call SQS rejects as the sender's fault alone, dropping one that still fails" in
    runIO {
      val sqs =
        new StubSqsClient({ (_, request) =>
          if (videoIds(request).contains("video-2")) throw sqsError(400, "AWS.SimpleQueueService.BatchRequestTooLong")
          else batchResponse(request, Map.empty)
        })

      new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(3)).attempt.map { result =>
        result mustBe Right(())
        sqs.requests.map(videoIds) mustBe
          List(List("video-1", "video-2", "video-3"), List("video-1"), List("video-2"), List("video-3"))
      }
    }

  it should "send each message alone when SQS reports the call too long with its own exception type" in runIO {
    val tooLong = BatchRequestTooLongException.builder().statusCode(400).message("boom").build()
    val sqs =
      new StubSqsClient({ (_, request) =>
        if (request.entries().size > 1) throw tooLong else batchResponse(request, Map.empty)
      })

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(2)).map { _ =>
      sqs.requests.map(videoIds) mustBe List(List("video-1", "video-2"), List("video-1"), List("video-2"))
    }
  }

  it should "retry, rather than drop, a call SQS throttles or denies, or rejects for any unlisted reason" in runIO {
    List(
      sqsError(400, "RequestThrottled"),
      sqsError(403, "AccessDenied"),
      sqsError(400, "ExpiredToken"),
      // e.g. a misconfigured queue URL: not known to be about the messages, so they must not all be dropped
      sqsError(400, "InvalidParameterValue"),
      sqsError(400, "AWS.SimpleQueueService.NonExistentQueue")
    )
      .traverse { error =>
        val sqs = new StubSqsClient((_, _) => throw error)

        new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(removals(2)).attempt.map { result =>
          result.left.map(_.getMessage.startsWith("SQS rejected 2 of 2 messages")) mustBe Left(true)
          // Never split: each attempt resends the whole batch
          sqs.requests.map(videoIds) mustBe List.fill(3)(List("video-1", "video-2"))
        }
      }
      .void
  }

  it should "split messages into batches of at most ten whose bodies fit in a batch's size limit" in runIO {
    // Each upsert's body is about 120,000 bytes, so only two fit under the limit
    val upserts =
      (1 to 5).toList.map { index =>
        fixtureUpsert.copy(videoId = s"video-$index", userIds = List.fill(10000)("user-1234"))
      }
    val sqs = new StubSqsClient((_, request) => batchResponse(request, Map.empty))

    new SqsFallbackSyncTransport[IO](sqs, "queue-url", noDelays).send(upserts ++ removals(12)).map { _ =>
      sqs.requests.map(videoIds) mustBe
        List(
          List("video-1", "video-2"),
          List("video-3", "video-4"),
          List("video-5") ++ (1 to 9).map(index => s"video-$index"),
          List("video-10", "video-11", "video-12")
        )
      sqs.requests.foreach { request =>
        request.entries().asScala.map(_.messageBody().getBytes(StandardCharsets.UTF_8).length.toLong).sum must be <=
          SqsFallbackSyncTransport.MaxBatchBytes
      }
    }
  }

  "SqsFallbackSyncTransport.batches" should "start a new batch when the next item would pass either limit" in {
    SqsFallbackSyncTransport.batches(List(4, 4, 3, 1, 1, 1, 9, 12, 1), maxEntries = 3, maxBytes = 10)(_.toLong) mustBe
      List(List(4, 4), List(3, 1, 1), List(1, 9), List(12), List(1))
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
        // One message per receive by default
        _ <- List("first", "second").traverse_ { body =>
          IO.fromCompletableFuture(
            IO(aws.sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body).build()))
          )
        }
        oneAtATime <- queue.receive
      } yield {
        received.map(message => (message.body, message.receiveCount)) mustBe List(("hello", 1))
        afterDelete mustBe empty
        oneAtATime.size mustBe 1
      }
    }
  }

  private def put(aws: FallbackSyncAwsClients, item: Map[String, AttributeValue]): IO[Unit] =
    IO.fromCompletableFuture(
      IO(aws.dynamoDb.putItem(PutItemRequest.builder().tableName("videos").item(item.asJava).build()))
    ).void

  private def s(value: String): AttributeValue = AttributeValue.builder().s(value).build()

  private def bool(value: Boolean): AttributeValue = AttributeValue.builder().bool(value).build()

  /** The table as fallback-api/template.yaml defines it, including the sparse GSI1 with projection ALL. */
  private def createVideosTable(aws: FallbackSyncAwsClients): IO[Unit] = {
    def key(name: String, keyType: KeyType) = KeySchemaElement.builder().attributeName(name).keyType(keyType).build()
    def attribute(name: String) =
      AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build()

    val createTable =
      CreateTableRequest
        .builder()
        .tableName("videos")
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .keySchema(key("PK", KeyType.HASH), key("SK", KeyType.RANGE))
        .attributeDefinitions(attribute("PK"), attribute("SK"), attribute("GSI1PK"), attribute("GSI1SK"))
        .globalSecondaryIndexes(
          GlobalSecondaryIndex
            .builder()
            .indexName("GSI1")
            .keySchema(key("GSI1PK", KeyType.HASH), key("GSI1SK", KeyType.RANGE))
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
            .build()
        )
        .build()

    IO.fromCompletableFuture(IO(aws.dynamoDb.createTable(createTable))).void
  }

  private def liveVideo(videoId: String, hash: String, capturedAt: String): Map[String, AttributeValue] =
    Map(
      "PK" -> s(s"VIDEO#$videoId"),
      "SK" -> s("VIDEO"),
      "hash" -> s(hash),
      "capturedAt" -> s(capturedAt),
      "deleted" -> bool(false),
      "GSI1PK" -> s("VIDEO"),
      "GSI1SK" -> s(s"2026-09-25T21:04:11.000000Z#$videoId")
    )

  "DynamoDbFallbackManifestReader" should "read live videos from GSI1 across scan pages, keeping malformed ones" in
    runIO {
      (DynamoDbLocalContainer.create[IO].flatMap(clients)).use { aws =>
        for {
          _ <- createVideosTable(aws)
          _ <- (1 to 30).toList.traverse_ { index =>
            val video = liveVideo(s"video-$index", s"hash-$index", "2026-09-26T08:15:30.123456Z")
            put(aws, video + ("padding" -> s("p" * 40000)))
          }
          // A tombstone, and a new video's lock placeholder: neither carries GSI1 attributes
          _ <- put(
            aws,
            Map(
              "PK" -> s("VIDEO#gone"),
              "SK" -> s("VIDEO"),
              "capturedAt" -> s("2026-09-26T08:15:30.123456Z"),
              "deleted" -> bool(true)
            )
          )
          _ <- put(
            aws,
            Map(
              "PK" -> s("VIDEO#locked-new"),
              "SK" -> s("VIDEO"),
              "deleted" -> bool(true),
              "lockId" -> s("lock-1"),
              "lockedUntil" -> s("2026-09-26T08:17:30.123456Z")
            )
          )
          _ <- put(aws, Map("PK" -> s("USER#user-1"), "SK" -> s("VIDEO#2026#video-1")))
          // A live video mid-way through a large apply carries extra lock attributes
          _ <- put(
            aws,
            liveVideo("locked-live", "hash-locked", "2026-09-26T08:15:30.123456Z") + ("lockId" -> s("lock-2"))
          )
          // Kept, under a hash that never matches, so the reconcile upserts or removes it
          _ <- put(aws, liveVideo("malformed-captured-at", "hash-malformed", "not-a-timestamp"))
          _ <- put(aws, liveVideo("missing-hash", "unused", "2026-09-26T08:15:30.123456Z") - "hash")
          manifest <- new DynamoDbFallbackManifestReader[IO](aws.dynamoDb, "videos").manifest
        } yield {
          val expected = Set("locked-live", "malformed-captured-at", "missing-hash")
          manifest.keySet mustBe ((1 to 30).map(index => s"video-$index").toSet ++ expected)
          manifest("video-7") mustBe ManifestEntry("hash-7", Instant.parse("2026-09-26T08:15:30.123456Z"))
          manifest("locked-live").hash mustBe "hash-locked"
          manifest("malformed-captured-at").hash mustBe ManifestEntry.MalformedHash
          manifest("missing-hash").hash mustBe ManifestEntry.MalformedHash
        }
      }
    }

  /** Converts contract JSON to the attribute values the fallback's boto3 resource layer writes. */
  private def attributeValue(json: Json): AttributeValue =
    json.fold(
      AttributeValue.builder().nul(true).build(),
      bool,
      number => AttributeValue.builder().n(number.toString).build(),
      s,
      values => AttributeValue.builder().l(values.map(attributeValue).asJava).build(),
      fields => AttributeValue.builder().m(fields.toMap.view.mapValues(attributeValue).toMap.asJava).build()
    )

  it should "read the fallback's stored item for the upsert contract fixture" in runIO {
    // The exact item the fallback's applier stores for scheduled-video-upsert.json, so renaming an attribute the
    // manifest reads, on either side, fails this test
    val item =
      ContractFixtures
        .json("dynamodb-video-item.json")
        .asObject
        .map(_.toMap.view.mapValues(attributeValue).toMap)
        .getOrElse(fail("The DynamoDB item fixture is not a JSON object"))

    (DynamoDbLocalContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        _ <- createVideosTable(aws)
        _ <- put(aws, item)
        manifest <- new DynamoDbFallbackManifestReader[IO](aws.dynamoDb, "videos").manifest
      } yield manifest mustBe Map(fixtureUpsert.videoId -> ManifestEntry(fixtureUpsert.hash, fixtureUpsert.capturedAt))
    }
  }

  "The contract fixture" should "fit in one SQS message" in {
    SyncJson.encode(fixtureUpsert).length must be < 1024 * 1024
  }
}
