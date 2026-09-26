package com.ruchij.api.services.fallback

import cats.Applicative
import cats.effect.{Async, Temporal}
import cats.implicits._
import cats.~>
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.types.Clock
import fs2.Stream

import scala.concurrent.duration._

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits.
class FallbackSyncPublisher[F[_]: Async: Clock, T[_]](
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  coordination: FallbackSyncCoordination[F],
  window: FiniteDuration = 30.seconds,
  maxBatchSize: Int = 500,
  retryDelays: List[FiniteDuration] = List(1.second, 5.seconds, 25.seconds)
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackSyncPublisher[Any, Any]]

  /** Reads the current state at send time, so a late duplicate still carries fresh data. Ids in `removedVideoIds`
    * become removals without a read (and win over any other event for the same id): an admin delete publishes a
    * `Deleted` event before the row is gone, so a read would still find it and send a stale upsert. */
  def messagesFor(
    videoIds: List[String],
    removedVideoIds: Set[String] = Set.empty
  ): F[List[MainToFallbackMessage]] =
    Clock[F].timestamp.flatMap { capturedAt =>
      (videoIds ++ removedVideoIds).distinct.traverse { videoId =>
        if (removedVideoIds.contains(videoId))
          Applicative[F].pure[MainToFallbackMessage](ScheduledVideoRemoval(videoId, capturedAt))
        else
          transaction(fallbackSyncDao.findById(videoId)).map[MainToFallbackMessage] {
            case Some(syncedVideo) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
            case None => ScheduledVideoRemoval(videoId, capturedAt)
          }
      }
    }

  /** Never fails: if the fallback stays unreachable, a reconcile is flagged to repair it later. */
  def publish(videoIds: List[String], removedVideoIds: Set[String] = Set.empty): F[Unit] =
    messagesFor(videoIds, removedVideoIds)
      .flatMap(sendWithRetries)
      .handleErrorWith { error =>
        logger.error[F](s"Fallback sync of ${videoIds.size} videos failed; flagging a reconcile", error) *>
          coordination.markReconcileNeeded
      }

  /** `isRemoval` marks events that must reach the fallback as removals whatever the database currently holds. */
  def pipeline[A](subscriber: Subscriber[F, A], groupId: String)(
    videoId: A => String,
    isRemoval: A => Boolean = (_: A) => false
  ): Stream[F, Unit] =
    subscriber
      .subscribe(groupId)
      .groupWithin(maxBatchSize, window)
      .evalMap { chunk =>
        val values = chunk.toList.map(subscriber.extractValue)

        publish(values.map(videoId), values.filter(isRemoval).map(videoId).toSet) *> subscriber.commit(chunk)
      }

  private def sendWithRetries(messages: List[MainToFallbackMessage]): F[Unit] =
    retryDelays.foldLeft(transport.send(messages)) { (attempt, delay) =>
      attempt.handleErrorWith(_ => Temporal[F].sleep(delay) *> transport.send(messages))
    }
}
