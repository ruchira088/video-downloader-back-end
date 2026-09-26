package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, FallbackSyncTransport, ReceivedMessage}
import com.ruchij.api.services.fallback.models.{RequestResolved, ResolutionOutcome, ScheduleRequest, SyncJson}
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.exceptions.{ResourceNotFoundException, UnsupportedVideoUrlException, ValidationException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration._

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits (see FallbackSyncPublisher / FallbackReconciler).
class FallbackRequestConsumer[F[_]: Async: Clock, T[_]](
  requestQueue: FallbackRequestQueue[F],
  schedulingService: ApiSchedulingService[F],
  userDao: UserDao[T],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
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
        resolve(request, message.receiveCount).flatMap {
          case Some(outcome) =>
            transport.send(List(RequestResolved(request.requestId, request.userId, outcome))) *>
              requestQueue.delete(message.receiptHandle)

          case None => Async[F].unit
        }
    }

  /** None means "transient, leave it on the queue for SQS to redeliver". */
  private def resolve(request: ScheduleRequest, receiveCount: Int): F[Option[ResolutionOutcome]] =
    transaction(userDao.findById(request.userId)).flatMap {
      case None => rejected(s"Unknown user: ${request.userId}")

      case Some(_) =>
        Uri.fromString(request.url) match {
          case Left(_) => rejected(s"Invalid URL: ${request.url}")

          case Right(uri) =>
            schedulingService.schedule(uri, request.userId).attempt.flatMap {
              case Right(result) =>
                scheduledOutcome(result.scheduledVideoDownload.videoMetadata.id).map(Option(_))

              case Left(error) if isPermanent(error) => rejected(errorMessage(error))

              case Left(error) if receiveCount >= maxReceiveCount =>
                rejected(s"Unable to schedule the video after $receiveCount attempts: ${errorMessage(error)}")

              case Left(error) =>
                logger
                  .warn[F](s"Transient failure scheduling request ${request.requestId}: ${errorMessage(error)}")
                  .as(Option.empty[ResolutionOutcome])
            }
        }
    }

  private def scheduledOutcome(videoId: String): F[ResolutionOutcome] =
    Clock[F].timestamp.flatMap { capturedAt =>
      transaction(fallbackSyncDao.findById(videoId)).flatMap {
        case Some(syncedVideo) =>
          Async[F].pure[ResolutionOutcome] {
            ResolutionOutcome.Scheduled(ScheduledVideoUpserts.from(syncedVideo, capturedAt))
          }

        case None => Async[F].raiseError(new IllegalStateException(s"Scheduled video $videoId was not found"))
      }
    }

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
