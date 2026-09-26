package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.api.services.fallback.models.{
  MainToFallbackMessage,
  RequestResolved,
  ScheduledVideoRemoval,
  ScheduledVideoUpsert,
  SyncJson
}
import com.ruchij.core.logging.Logger
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{SendMessageBatchRequest, SendMessageBatchRequestEntry}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

trait FallbackSyncTransport[F[_]] {
  def send(messages: List[MainToFallbackMessage]): F[Unit]
}

/** Sends every batch of 10, even after an earlier batch failed, so one bad batch can't hold back the rest (removals,
  * sent last by the reconcile, would otherwise never go out). An entry SQS rejects as the sender's fault (e.g. a
  * message too large) can never succeed: it is logged and dropped rather than failing the send, which would only
  * flag a reconcile that sends it again. Transient failures, of an entry or of a whole call, are retried after each
  * of `retryDelays` and raised together once every batch has been attempted. */
class SqsFallbackSyncTransport[F[_]: Async](
  sqsClient: SqsAsyncClient,
  queueUrl: String,
  retryDelays: List[FiniteDuration] = List(200.millis, 1.second)
) extends FallbackSyncTransport[F] {
  private val logger = Logger[SqsFallbackSyncTransport[F]]

  override def send(messages: List[MainToFallbackMessage]): F[Unit] =
    messages
      .grouped(10)
      .toList
      .flatTraverse { batch =>
        sendBatch(batch.zipWithIndex.map { case (message, index) => index.toString -> message }, retryDelays)
      }
      .flatMap { failures =>
        if (failures.isEmpty) Async[F].unit
        else
          Async[F].raiseError[Unit] {
            new IllegalStateException(
              s"SQS rejected ${failures.size} of ${messages.size} messages: ${failures.mkString("; ")}"
            )
          }
      }

  /** Returns a description of each entry that still failed transiently once the retries ran out. */
  private def sendBatch(
    entries: List[(String, MainToFallbackMessage)],
    delays: List[FiniteDuration]
  ): F[List[String]] =
    attempt(entries).flatMap { failures =>
      (failures, delays) match {
        case (Nil, _) => Async[F].pure(List.empty[String])

        case (_, Nil) =>
          val messages = entries.toMap
          Async[F].pure(failures.map { case (id, reason) => s"${messages.get(id).fold(id)(describe)}: $reason" })

        case (_, delay :: remaining) =>
          val failedIds = failures.map(_._1).toSet
          Async[F].sleep(delay) *> sendBatch(entries.filter(entry => failedIds.contains(entry._1)), remaining)
      }
    }

  /** Returns the id and reason of each transient failure. */
  private def attempt(entries: List[(String, MainToFallbackMessage)]): F[List[(String, String)]] = {
    val request =
      SendMessageBatchRequest
        .builder()
        .queueUrl(queueUrl)
        .entries(
          entries.map {
            case (id, message) =>
              SendMessageBatchRequestEntry.builder().id(id).messageBody(SyncJson.encode(message)).build()
          }.asJava
        )
        .build()

    Async[F]
      .fromCompletableFuture(Sync[F].delay(sqsClient.sendMessageBatch(request)))
      .flatMap { response =>
        // SendMessageBatch reports per-entry failures inside a successful response
        val (permanent, transient) = response.failed().asScala.toList.partition(_.senderFault())

        permanent
          .traverse_ { failure =>
            val message = entries.toMap.get(failure.id()).fold(failure.id())(describe)
            logger.error[F](
              s"SQS permanently rejected fallback sync message $message; dropping it",
              new IllegalArgumentException(s"${failure.code()}: ${failure.message()}")
            )
          }
          .as(transient.map(failure => failure.id() -> s"${failure.code()} ${failure.message()}"))
      }
      .handleError(error => entries.map { case (id, _) => id -> error.toString })
  }

  private def describe(message: MainToFallbackMessage): String =
    message match {
      case upsert: ScheduledVideoUpsert => s"ScheduledVideoUpsert for video ${upsert.videoId}"
      case removal: ScheduledVideoRemoval => s"ScheduledVideoRemoval for video ${removal.videoId}"
      case resolved: RequestResolved => s"RequestResolved for request ${resolved.requestId}"
    }
}
