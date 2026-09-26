package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import cats.{Monad, ~>}
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, FallbackSyncTransport, ReceivedMessage}
import com.ruchij.api.services.fallback.models.{
  RequestResolved,
  ResolutionOutcome,
  ResolvedRequestKey,
  ScheduleRequest,
  SyncJson
}
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.exceptions.{ResourceNotFoundException, UnsupportedVideoUrlException, ValidationException}
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.logging.Logger
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration._

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits (see FallbackSyncPublisher / FallbackReconciler).
class FallbackRequestConsumer[F[_]: Async, T[_]: Monad](
  requestQueue: FallbackRequestQueue[F],
  schedulingService: ApiSchedulingService[F],
  userDao: UserDao[T],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  resolvedRequests: KeySpacedKeyValueStore[F, ResolvedRequestKey, String],
  pollInterval: FiniteDuration = 60.seconds,
  maxReceiveCount: Int = 5
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackRequestConsumer[Any, Any]]

  val run: Stream[F, Unit] =
    Stream
      .fixedRateStartImmediately[F](pollInterval)
      .evalMap { _ =>
        drain.handleErrorWith(error => logger.error[F]("Polling fallback schedule requests failed", error))
      }

  lazy val drain: F[Unit] =
    requestQueue.receive.flatMap { messages =>
      if (messages.isEmpty) Async[F].unit
      else messages.traverse_(message => handle(message).handleErrorWith(logFailure(message))) *> drain
    }

  def handle(message: ReceivedMessage): F[Unit] =
    SyncJson.decodeScheduleRequest(message.body) match {
      case Left(error) =>
        // Without a request id no reply is possible; leaving it lets SQS dead-letter it and raise the alarm.
        logger.warn[F] {
          s"Leaving an undecodable fallback schedule request for the dead-letter queue: ${error.getMessage}"
        }

      case Right(request) =>
        storedReply(request.requestId).flatMap {
          case Some(reply) =>
            // Already handled, e.g. the delete below failed: scheduling again could bring back a video the user
            // has deleted since, so only the reply is repeated
            logger.info[F](s"Re-sending the stored reply to fallback schedule request ${request.requestId}") *>
              transport.send(List(reply)) *>
              requestQueue.delete(message.receiptHandle)

          case None =>
            resolve(request, message.receiveCount).flatMap {
              case Some(outcome) =>
                val reply = RequestResolved(request.requestId, request.userId, outcome)

                // Stored before sending, so a reply that fails to send is also only re-sent, never re-resolved
                storeReply(reply) *> transport.send(List(reply)) *> requestQueue.delete(message.receiptHandle)

              case None => Async[F].unit
            }
        }
    }

  /** Best effort: if the key-value store is down, requests are resolved as if never seen, as before this existed. */
  private def storedReply(requestId: String): F[Option[RequestResolved]] =
    resolvedRequests
      .get(ResolvedRequestKey(requestId))
      .flatMap {
        _.flatTraverse { stored =>
          SyncJson.decodeRequestResolved(stored) match {
            case Right(reply) => Async[F].pure(Option(reply))
            case Left(error) =>
              logger
                .warn[F](s"Ignoring the unreadable stored reply to request $requestId: $error")
                .as(Option.empty[RequestResolved])
          }
        }
      }
      .handleErrorWith { error =>
        logger
          .warn[F](s"Unable to look up a stored reply to request $requestId: $error")
          .as(Option.empty[RequestResolved])
      }

  private def storeReply(reply: RequestResolved): F[Unit] =
    resolvedRequests.put(ResolvedRequestKey(reply.requestId), SyncJson.encode(reply)).handleErrorWith { error =>
      logger.warn[F](s"Unable to store the reply to request ${reply.requestId}: $error")
    }

  /** Classifies every failure that can occur while resolving a request -- user lookup, URL parsing, scheduling
    * and the post-schedule state read -- so a persistent failure anywhere in that path still gets a reply once
    * `maxReceiveCount` is reached, instead of the message being silently dead-lettered by SQS with no reply ever
    * sent (the plan reserves that dead-letter path for undecodable messages and failed replies only).
    * None means "transient, leave it on the queue for SQS to redeliver". */
  private def resolve(request: ScheduleRequest, receiveCount: Int): F[Option[ResolutionOutcome]] =
    resolution(request).attempt.flatMap {
      case Right(outcome) => Async[F].pure(Option(outcome))

      case Left(error) if isPermanent(error) => rejected(errorMessage(error))

      case Left(error) if receiveCount >= maxReceiveCount =>
        logger.error[F](s"Giving up on request ${request.requestId} after $receiveCount attempts", error) *>
          rejected(
            s"Unable to schedule the video right now (gave up after $receiveCount attempts); please try again later"
          )

      case Left(error) =>
        logger
          .warn[F](s"Transient failure resolving request ${request.requestId}: ${errorMessage(error)}")
          .as(Option.empty[ResolutionOutcome])
    }

  /** Unknown user / an unparseable URL resolve immediately, regardless of receive count -- they are ordinary
    * outcomes, not thrown failures, so they pass straight through the `attempt` above as `Right`. Everything
    * past the URL parse can genuinely fail (DB errors, the scheduling call, the post-schedule state read), and
    * those failures are what `resolve` classifies as permanent, exhausted or transient. */
  private def resolution(request: ScheduleRequest): F[ResolutionOutcome] =
    transaction(userDao.findById(request.userId)).flatMap {
      case None =>
        // The reply is shown to whoever sent the request, so it doesn't say which user ids exist
        logger
          .warn[F](s"Rejecting fallback schedule request ${request.requestId} for unknown user ${request.userId}")
          .as(ResolutionOutcome.Rejected("Unable to schedule videos for this account"))

      case Some(_) if request.url.length > MaxUrlLength =>
        Async[F].pure[ResolutionOutcome] {
          ResolutionOutcome.Rejected(s"URLs longer than $MaxUrlLength characters are not supported")
        }

      case Some(_) =>
        // Like the main API's own schedule route, which drops the fragment too
        Uri.fromString(request.url).map(_.withoutFragment) match {
          case Left(_) =>
            Async[F].pure[ResolutionOutcome](ResolutionOutcome.Rejected(s"Invalid URL: ${request.url}"))

          case Right(uri) =>
            schedulingService.schedule(uri, request.userId).flatMap { result =>
              scheduledOutcome(result.scheduledVideoDownload.videoMetadata.id)
            }
        }
    }

  private def scheduledOutcome(videoId: String): F[ResolutionOutcome] =
    // capturedAt comes from the database clock, in the same transaction as the read it stamps
    transaction(fallbackSyncDao.currentTimestamp.product(fallbackSyncDao.findById(videoId))).flatMap {
      case (capturedAt, Some(syncedVideo)) =>
        Async[F].pure[ResolutionOutcome] {
          ResolutionOutcome.Scheduled(ScheduledVideoUpserts.from(syncedVideo, capturedAt))
        }

      case (_, None) => Async[F].raiseError(new IllegalStateException(s"Scheduled video $videoId was not found"))
    }

  private val MaxUrlLength = 2048

  private def rejected(reason: String): F[Option[ResolutionOutcome]] =
    Async[F].pure(Option(ResolutionOutcome.Rejected(reason)))

  private def isPermanent(error: Throwable): Boolean =
    error match {
      case _: ValidationException | _: UnsupportedVideoUrlException | _: ResourceNotFoundException => true
      case _ => false
    }

  private def errorMessage(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)

  private def logFailure(message: ReceivedMessage)(error: Throwable): F[Unit] =
    logger.error[F](s"Handling fallback schedule request (receive ${message.receiveCount}) failed", error)
}
