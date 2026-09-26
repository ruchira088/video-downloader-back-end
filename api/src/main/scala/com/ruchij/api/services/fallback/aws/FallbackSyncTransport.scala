package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.api.services.fallback.aws.SqsFallbackSyncTransport.{Entry, MaxBatchBytes, MaxBatchEntries}
import com.ruchij.api.services.fallback.models.{
  MainToFallbackMessage,
  RequestResolved,
  ScheduledVideoRemoval,
  ScheduledVideoUpsert,
  SyncJson
}
import com.ruchij.core.logging.Logger
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  BatchEntryIdsNotDistinctException,
  BatchRequestTooLongException,
  EmptyBatchRequestException,
  InvalidBatchEntryIdException,
  InvalidMessageContentsException,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry,
  SqsException,
  TooManyEntriesInBatchRequestException
}

import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletionException, ExecutionException}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

trait FallbackSyncTransport[F[_]] {
  def send(messages: List[MainToFallbackMessage]): F[Unit]
}

/** Sends messages in batches of at most 10 whose bodies total at most `MaxBatchBytes`, and sends every batch even
  * after an earlier batch failed, so one bad batch can't hold back the rest (removals, sent last by the reconcile,
  * would otherwise never go out).
  *
  * A message SQS rejects as the sender's fault (e.g. one too large) can never succeed: it is logged and dropped
  * rather than failing the send, which would only flag a reconcile that sends it again, forever. SQS reports this
  * either for an entry, inside a successful response, or for the whole call (e.g. BatchRequestTooLong). A call it
  * rejects as the sender's fault is split and each of its messages sent alone, so only a message that still fails
  * alone is dropped. Only the call errors known to be about the messages count as the sender's fault; every other
  * failure, of an entry or of a whole call, is transient, including throttling, denied or expired credentials, a
  * missing queue or KMS key and any client error not known to be about the messages (so a misconfiguration can't
  * make every message be dropped): it is retried after each of `retryDelays`, and those still failing are raised
  * together once every batch has been attempted. */
class SqsFallbackSyncTransport[F[_]: Async](
  sqsClient: SqsAsyncClient,
  queueUrl: String,
  retryDelays: List[FiniteDuration] = List(200.millis, 1.second)
) extends FallbackSyncTransport[F] {
  private val logger = Logger[SqsFallbackSyncTransport[F]]

  override def send(messages: List[MainToFallbackMessage]): F[Unit] =
    SqsFallbackSyncTransport
      .batches(messages.map(message => message -> SyncJson.encode(message)), MaxBatchEntries, MaxBatchBytes) {
        case (_, body) => body.getBytes(StandardCharsets.UTF_8).length.toLong
      }
      .flatTraverse { batch =>
        val entries = batch.zipWithIndex.map { case ((message, body), index) => Entry(index.toString, message, body) }
        sendBatch(entries, retryDelays)
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
  private def sendBatch(entries: List[Entry], delays: List[FiniteDuration]): F[List[String]] =
    attempt(entries).flatMap { failures =>
      (failures, delays) match {
        case (Nil, _) => Async[F].pure(List.empty[String])

        case (_, Nil) =>
          val messages = entries.map(entry => entry.id -> entry.message).toMap
          Async[F].pure(failures.map { case (id, reason) => s"${messages.get(id).fold(id)(describe)}: $reason" })

        case (_, delay :: remaining) =>
          val failedIds = failures.map(_._1).toSet
          Async[F].sleep(delay) *> sendBatch(entries.filter(entry => failedIds.contains(entry.id)), remaining)
      }
    }

  /** Returns the id and reason of each transient failure. */
  private def attempt(entries: List[Entry]): F[List[(String, String)]] = {
    val request =
      SendMessageBatchRequest
        .builder()
        .queueUrl(queueUrl)
        .entries(
          entries.map { entry =>
            SendMessageBatchRequestEntry.builder().id(entry.id).messageBody(entry.body).build()
          }.asJava
        )
        .build()

    Async[F]
      .fromCompletableFuture(Sync[F].delay(sqsClient.sendMessageBatch(request)))
      .attempt
      .flatMap {
        case Right(response) =>
          // SendMessageBatch reports per-entry failures inside a successful response
          val (permanent, transient) = response.failed().asScala.toList.partition(_.senderFault())
          val messages = entries.map(entry => entry.id -> entry.message).toMap

          permanent
            .traverse_ { failure =>
              drop(
                messages.get(failure.id()).fold(failure.id())(describe),
                new IllegalArgumentException(s"${failure.code()}: ${failure.message()}")
              )
            }
            .as(transient.map(failure => failure.id() -> s"${failure.code()} ${failure.message()}"))

        case Left(error) =>
          SqsFallbackSyncTransport.unwrap(error) match {
            case senderFault if SqsFallbackSyncTransport.isSenderFault(senderFault) =>
              entries match {
                case single :: Nil => drop(describe(single.message), senderFault).as(List.empty[(String, String)])

                case _ =>
                  logger.warn[F] {
                    s"SQS rejected a batch of ${entries.size} fallback sync messages as the sender's fault " +
                      s"(${senderFault.getMessage}); sending each alone"
                  } *> entries.flatTraverse(entry => attempt(List(entry)))
              }

            case transient => Async[F].pure(entries.map(entry => entry.id -> transient.toString))
          }
      }
  }

  private def drop(message: String, reason: Throwable): F[Unit] =
    logger.error[F](s"SQS permanently rejected fallback sync message $message; dropping it", reason)

  private def describe(message: MainToFallbackMessage): String =
    message match {
      case upsert: ScheduledVideoUpsert => s"ScheduledVideoUpsert for video ${upsert.videoId}"
      case removal: ScheduledVideoRemoval => s"ScheduledVideoRemoval for video ${removal.videoId}"
      case resolved: RequestResolved => s"RequestResolved for request ${resolved.requestId}"
    }
}

object SqsFallbackSyncTransport {
  val MaxBatchEntries: Int = 10

  /** SendMessageBatch rejects a call whose bodies total more than the queue's limit, 256 KiB unless the queue's
    * maximum message size was raised, so batches stay below it with a margin. A single message larger than this is
    * still sent, alone, leaving SQS to decide whether it is too large for the queue. */
  val MaxBatchBytes: Long = 240 * 1024

  private final case class Entry(id: String, message: MainToFallbackMessage, body: String)

  /** Groups items in order, starting a new batch whenever the next item would take the current one past either
    * limit. An item larger than `maxBytes` goes in a batch of its own. */
  def batches[A](items: List[A], maxEntries: Int, maxBytes: Long)(size: A => Long): List[List[A]] =
    items
      .foldLeft(List.empty[(List[A], Long)]) {
        case ((current, bytes) :: done, item) if current.size < maxEntries && bytes + size(item) <= maxBytes =>
          (item :: current, bytes + size(item)) :: done

        case (done, item) => (List(item), size(item)) :: done
      }
      .map { case (batch, _) => batch.reverse }
      .reverse

  /** Codes of the call errors caused by the messages sent, as either protocol reports them, e.g.
    * "BatchRequestTooLong" or "AWS.SimpleQueueService.BatchRequestTooLong". */
  private val SenderFaultErrorCodes: Set[String] =
    Set(
      "BatchEntryIdsNotDistinct",
      "BatchRequestTooLong",
      "EmptyBatchRequest",
      "InvalidBatchEntryId",
      "InvalidMessageContents",
      "TooManyEntriesInBatchRequest"
    )

  /** Whether SQS rejected a whole call because of the messages in it, e.g. BatchRequestTooLong (a single message too
    * large for the queue included) or InvalidMessageContents. An allowlist: any other error, such as a generic 400
    * from a misconfigured queue URL, is left transient, since treating it as the messages' fault would drop them all. */
  def isSenderFault(error: Throwable): Boolean =
    error match {
      case _: BatchRequestTooLongException | _: InvalidMessageContentsException |
          _: BatchEntryIdsNotDistinctException | _: EmptyBatchRequestException | _: InvalidBatchEntryIdException |
          _: TooManyEntriesInBatchRequestException =>
        true

      case sqsException: SqsException =>
        Option(sqsException.awsErrorDetails())
          .flatMap(details => Option(details.errorCode()))
          .map(_.stripPrefix("AWS.SimpleQueueService."))
          .exists(SenderFaultErrorCodes.contains)

      case _ => false
    }

  private def unwrap(error: Throwable): Throwable =
    error match {
      case wrapper @ (_: CompletionException | _: ExecutionException) if wrapper.getCause != null =>
        unwrap(wrapper.getCause)

      case _ => error
    }
}
