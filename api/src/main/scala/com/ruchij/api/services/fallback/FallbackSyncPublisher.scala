package com.ruchij.api.services.fallback

import cats.effect.{Async, Temporal}
import cats.implicits._
import cats.{Monad, ~>}
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration._

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits.
class FallbackSyncPublisher[F[_]: Async, T[_]: Monad](
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
    (videoIds ++ removedVideoIds).distinct.traverse { videoId =>
      // capturedAt comes from the database clock, in the same transaction as the read it stamps
      readWithTimestamp(videoId).map[MainToFallbackMessage] {
        case (capturedAt, _) if removedVideoIds.contains(videoId) => ScheduledVideoRemoval(videoId, capturedAt)
        case (capturedAt, Some(syncedVideo)) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
        case (capturedAt, None) => ScheduledVideoRemoval(videoId, capturedAt)
      }
    }

  private def readWithTimestamp(videoId: String): F[(Instant, Option[SyncedVideo])] =
    transaction(fallbackSyncDao.currentTimestamp.product(fallbackSyncDao.findById(videoId)))

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
