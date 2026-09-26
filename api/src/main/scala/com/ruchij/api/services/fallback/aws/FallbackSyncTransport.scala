package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, SyncJson}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{SendMessageBatchRequest, SendMessageBatchRequestEntry}

import scala.jdk.CollectionConverters._

trait FallbackSyncTransport[F[_]] {
  def send(messages: List[MainToFallbackMessage]): F[Unit]
}

class SqsFallbackSyncTransport[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String)
    extends FallbackSyncTransport[F] {

  override def send(messages: List[MainToFallbackMessage]): F[Unit] =
    messages.grouped(10).toList.traverse_ { batch =>
      val entries =
        batch.zipWithIndex.map {
          case (message, index) =>
            SendMessageBatchRequestEntry.builder().id(index.toString).messageBody(SyncJson.encode(message)).build()
        }

      val request = SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries.asJava).build()

      Async[F].fromCompletableFuture(Sync[F].delay(sqsClient.sendMessageBatch(request))).flatMap { response =>
        // SendMessageBatch reports per-entry failures inside a successful response.
        if (response.failed().isEmpty) Async[F].unit
        else
          Async[F].raiseError[Unit](
            new IllegalStateException(
              s"SQS rejected ${response.failed().size()} of ${entries.size} messages: " +
                response.failed().asScala.map(_.message()).mkString("; ")
            )
          )
      }
    }
}
