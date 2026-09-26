package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.core.logging.Logger
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, ScanRequest}

import java.time.Instant
import java.util.{Map => JMap}
import scala.jdk.CollectionConverters._

final case class ManifestEntry(hash: String, capturedAt: Instant)

object ManifestEntry {
  /** Stands in for the hash of an item the reader can't parse. SyncHash is 16 hex characters, so this never matches:
    * the reconcile then upserts the video if it still exists on the main side and removes it otherwise. */
  val MalformedHash = "malformed"
}

trait FallbackManifestReader[F[_]] {
  def manifest: F[Map[String, ManifestEntry]]
}

/** Scans the sparse GSI1, which holds exactly the live videos: the fallback gives a video item GSI1 attributes when
  * it is live and drops them when it tombstones it (or while a new video's item is only a lock placeholder). The
  * index projects every attribute, so the scan reads the hash and capturedAt from it directly. */
class DynamoDbFallbackManifestReader[F[_]: Async](dynamoDbClient: DynamoDbAsyncClient, tableName: String)
    extends FallbackManifestReader[F] {
  private val logger = Logger[DynamoDbFallbackManifestReader[F]]

  override def manifest: F[Map[String, ManifestEntry]] = page(None, Map.empty)

  private def page(
    exclusiveStartKey: Option[JMap[String, AttributeValue]],
    accumulated: Map[String, ManifestEntry]
  ): F[Map[String, ManifestEntry]] = {
    val builder =
      ScanRequest
        .builder()
        .tableName(tableName)
        .indexName(DynamoDbFallbackManifestReader.IndexName)
        .projectionExpression("PK, #hash, capturedAt")
        // GSI1 holds live videos only; the filter just guards against an item that is somehow both
        .filterExpression("attribute_not_exists(deleted) OR deleted = :false")
        .expressionAttributeNames(Map("#hash" -> "hash").asJava)
        .expressionAttributeValues(Map(":false" -> AttributeValue.builder().bool(false).build()).asJava)

    val request = exclusiveStartKey.fold(builder)(builder.exclusiveStartKey).build()

    for {
      response <- Async[F].fromCompletableFuture(Sync[F].delay(dynamoDbClient.scan(request)))
      entries <- response.items().asScala.toList.flatTraverse(entry)
      next = accumulated ++ entries
      result <-
        if (response.hasLastEvaluatedKey && !response.lastEvaluatedKey().isEmpty)
          page(Some(response.lastEvaluatedKey()), next)
        else Async[F].pure(next)
    } yield result
  }

  /** An item whose hash or capturedAt is missing or unparseable stays in the manifest under `MalformedHash` instead
    * of failing the scan or being skipped: skipped, a video gone from the main side would never be removed. */
  private def entry(item: JMap[String, AttributeValue]): F[List[(String, ManifestEntry)]] = {
    def string(name: String): Option[String] = Option(item.get(name)).flatMap(value => Option(value.s()))

    string("PK").map(_.stripPrefix("VIDEO#")) match {
      case None => logger.warn[F](s"Ignoring a fallback manifest item without a PK: $item").as(Nil)

      case Some(videoId) =>
        val parsed =
          for {
            hash <- string("hash")
            capturedAt <- string("capturedAt").flatMap(value => Either.catchNonFatal(Instant.parse(value)).toOption)
          } yield ManifestEntry(hash, capturedAt)

        parsed match {
          case Some(manifestEntry) => Async[F].pure(List(videoId -> manifestEntry))

          case None =>
            logger
              .warn[F](s"Fallback manifest item for video $videoId has no valid hash or capturedAt; re-syncing it")
              .as(List(videoId -> ManifestEntry(ManifestEntry.MalformedHash, Instant.EPOCH)))
        }
    }
  }
}

object DynamoDbFallbackManifestReader {
  val IndexName = "GSI1"
}
