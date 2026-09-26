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
    * with a `Deleted` event to the latest such event's timestamp. An admin delete publishes `Deleted` before batch
    * hard-deletes the row, so a row that is still there only becomes an upsert when it was scheduled after the
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
    transaction(fallbackSyncDao.timestamped(fallbackSyncDao.findById(videoId)))

  /** Never fails: if the fallback stays unreachable, a reconcile is flagged to repair it later. */
  def publish(videoIds: List[String], deletions: Map[String, Instant] = Map.empty): F[Unit] =
    messagesFor(videoIds, deletions)
      .flatMap(sendWithRetries)
      .handleErrorWith { error =>
        logger.error[F](s"Fallback sync of ${videoIds.size} videos failed; flagging a reconcile", error) *>
          coordination.markReconcileNeeded
      }

  /** `deletedAt` gives the timestamp of an event that deletes its video. Within a window, the latest `Deleted` event
    * for an id counts even when other events for it follow, since an event that only updates the row awaiting its
    * hard delete (e.g. an admin changing its status) must not turn the removal into an upsert. The row's scheduledAt
    * tells the two apart: only a row scheduled after the deletion, i.e. the URL scheduled again, is upserted. */
  def pipeline[A](subscriber: Subscriber[F, A], groupId: String)(
    videoId: A => String,
    deletedAt: A => Option[Instant] = (_: A) => None
  ): Stream[F, Unit] =
    subscriber
      .subscribe(groupId)
      .groupWithin(maxBatchSize, window)
      .evalMap { chunk =>
        val values = chunk.toList.map(subscriber.extractValue)
        val deletions =
          values
            .flatMap(value => deletedAt(value).map(videoId(value) -> _))
            .groupMapReduce { case (id, _) => id } { case (_, timestamp) => timestamp }(Ordering[Instant].max)

        publish(values.map(videoId), deletions) *> subscriber.commit(chunk)
      }

  private def sendWithRetries(messages: List[MainToFallbackMessage]): F[Unit] =
    retryDelays.foldLeft(transport.send(messages)) { (attempt, delay) =>
      attempt.handleErrorWith(_ => Temporal[F].sleep(delay) *> transport.send(messages))
    }
}
