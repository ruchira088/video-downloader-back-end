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

  /** Reads the current state at send time, so a late duplicate still carries fresh data. `deletions` maps each id
    * whose latest event was a `Deleted` event to that event's timestamp. An admin delete publishes `Deleted` before
    * batch hard-deletes the row, so a row that is still there only becomes an upsert when it was scheduled after the
    * deletion, i.e. its URL was scheduled again (a replayed `Deleted` event must not tombstone that live video);
    * otherwise the row is awaiting its hard delete and the id becomes a removal. */
  def messagesFor(
    videoIds: List[String],
    deletions: Map[String, Instant] = Map.empty
  ): F[List[MainToFallbackMessage]] =
    (videoIds ++ deletions.keys.toList.sorted).distinct.traverse { videoId =>
      // capturedAt comes from the database clock, in the same transaction as the read it stamps
      readWithTimestamp(videoId).map[MainToFallbackMessage] {
        case (capturedAt, Some(syncedVideo))
            if deletions.get(videoId).forall(syncedVideo.scheduledVideoDownload.scheduledAt.isAfter) =>
          ScheduledVideoUpserts.from(syncedVideo, capturedAt)

        case (capturedAt, _) => ScheduledVideoRemoval(videoId, capturedAt)
      }
    }

  private def readWithTimestamp(videoId: String): F[(Instant, Option[SyncedVideo])] =
    transaction(fallbackSyncDao.currentTimestamp.product(fallbackSyncDao.findById(videoId)))

  /** Never fails: if the fallback stays unreachable, a reconcile is flagged to repair it later. */
  def publish(videoIds: List[String], deletions: Map[String, Instant] = Map.empty): F[Unit] =
    messagesFor(videoIds, deletions)
      .flatMap(sendWithRetries)
      .handleErrorWith { error =>
        logger.error[F](s"Fallback sync of ${videoIds.size} videos failed; flagging a reconcile", error) *>
          coordination.markReconcileNeeded
      }

  /** `deletedAt` gives the timestamp of an event that deletes its video. Within a window only the latest event for
    * each id counts, so a `Deleted` event followed by the URL being scheduled again syncs the new row. */
  def pipeline[A](subscriber: Subscriber[F, A], groupId: String)(
    videoId: A => String,
    deletedAt: A => Option[Instant] = (_: A) => None
  ): Stream[F, Unit] =
    subscriber
      .subscribe(groupId)
      .groupWithin(maxBatchSize, window)
      .evalMap { chunk =>
        val values = chunk.toList.map(subscriber.extractValue)
        // toMap keeps the last value for a repeated key, so each id maps to its latest event's deletion
        val deletions =
          values.map(value => videoId(value) -> deletedAt(value)).toMap.collect {
            case (id, Some(timestamp)) => id -> timestamp
          }

        publish(values.map(videoId), deletions) *> subscriber.commit(chunk)
      }

  private def sendWithRetries(messages: List[MainToFallbackMessage]): F[Unit] =
    retryDelays.foldLeft(transport.send(messages)) { (attempt, delay) =>
      attempt.handleErrorWith(_ => Temporal[F].sleep(delay) *> transport.send(messages))
    }
}
