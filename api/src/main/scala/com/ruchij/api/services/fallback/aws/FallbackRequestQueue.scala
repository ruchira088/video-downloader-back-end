package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  DeleteMessageRequest,
  MessageSystemAttributeName,
  ReceiveMessageRequest
}

import scala.jdk.CollectionConverters._

final case class ReceivedMessage(body: String, receiptHandle: String, receiveCount: Int)

trait FallbackRequestQueue[F[_]] {
  def receive: F[List[ReceivedMessage]]

  def delete(receiptHandle: String): F[Unit]
}

class SqsFallbackRequestQueue[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String, waitTimeSeconds: Int = 20)
    extends FallbackRequestQueue[F] {

  override def receive: F[List[ReceivedMessage]] = {
    val request =
      ReceiveMessageRequest
        .builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(10)
        .waitTimeSeconds(waitTimeSeconds)
        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
        .build()

    Async[F].fromCompletableFuture(Sync[F].delay(sqsClient.receiveMessage(request))).map { response =>
      response.messages().asScala.toList.map { message =>
        val receiveCount =
          message
            .attributes()
            .asScala
            .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .flatMap(_.toIntOption)
            .getOrElse(1)

        ReceivedMessage(message.body(), message.receiptHandle(), receiveCount)
      }
    }
  }

  override def delete(receiptHandle: String): F[Unit] =
    Async[F]
      .fromCompletableFuture(
        Sync[F].delay(
          sqsClient.deleteMessage(
            DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build()
          )
        )
      )
      .void
}
