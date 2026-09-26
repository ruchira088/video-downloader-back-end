package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, ScanRequest}

import java.time.Instant
import java.util.{Map => JMap}
import scala.jdk.CollectionConverters._

final case class ManifestEntry(hash: String, capturedAt: Instant)

trait FallbackManifestReader[F[_]] {
  def manifest: F[Map[String, ManifestEntry]]
}

class DynamoDbFallbackManifestReader[F[_]: Async](dynamoDbClient: DynamoDbAsyncClient, tableName: String)
    extends FallbackManifestReader[F] {

  override def manifest: F[Map[String, ManifestEntry]] = page(None, Map.empty)

  private def page(
    exclusiveStartKey: Option[JMap[String, AttributeValue]],
    accumulated: Map[String, ManifestEntry]
  ): F[Map[String, ManifestEntry]] = {
    val builder =
      ScanRequest
        .builder()
        .tableName(tableName)
        .projectionExpression("PK, #hash, capturedAt")
        .filterExpression("SK = :video AND (attribute_not_exists(deleted) OR deleted = :false)")
        .expressionAttributeNames(Map("#hash" -> "hash").asJava)
        .expressionAttributeValues(
          Map(
            ":video" -> AttributeValue.builder().s("VIDEO").build(),
            ":false" -> AttributeValue.builder().bool(false).build()
          ).asJava
        )

    val request = exclusiveStartKey.fold(builder)(builder.exclusiveStartKey).build()

    Async[F].fromCompletableFuture(Sync[F].delay(dynamoDbClient.scan(request))).flatMap { response =>
      val entries =
        response.items().asScala.toList.flatMap { item =>
          for {
            partitionKey <- Option(item.get("PK")).map(_.s())
            hash <- Option(item.get("hash")).map(_.s())
            capturedAt <- Option(item.get("capturedAt")).map(_.s())
          } yield partitionKey.stripPrefix("VIDEO#") -> ManifestEntry(hash, Instant.parse(capturedAt))
        }

      val next = accumulated ++ entries

      if (response.hasLastEvaluatedKey && !response.lastEvaluatedKey().isEmpty)
        page(Some(response.lastEvaluatedKey()), next)
      else Async[F].pure(next)
    }
  }
}
