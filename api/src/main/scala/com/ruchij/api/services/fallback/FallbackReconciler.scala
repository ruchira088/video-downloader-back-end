package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import cats.~>
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, FallbackSyncTransport}
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration._

final case class ReconcileSummary(upserts: Int, removals: Int)

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits (see FallbackSyncPublisher).
class FallbackReconciler[F[_]: Async: Clock, T[_]](
  manifestReader: FallbackManifestReader[F],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  coordination: FallbackSyncCoordination[F],
  instanceId: String
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackReconciler[Any, Any]]

  /** None when another instance holds the reconcile lock. */
  val reconcile: F[Option[ReconcileSummary]] =
    coordination.withReconcileLock(instanceId) {
      for {
        // The manifest must be read before the DB: a video synced between the two reads then shows up as an
        // extra (harmless) upsert instead of being wrongly removed.
        manifest <- manifestReader.manifest
        capturedAt <- Clock[F].timestamp
        videos <- transaction(fallbackSyncDao.findAll)
        diff = ReconcileDiff.compute(manifest, videos.map(ScheduledVideoUpserts.from(_, capturedAt)))
        removals <- confirmedRemovals(diff.removedVideoIds, capturedAt)
        _ <- transport.send(diff.upserts ++ removals)
        _ <- coordination.clearReconcileNeeded
        summary = ReconcileSummary(diff.upserts.size, removals.count(_.isInstanceOf[ScheduledVideoRemoval]))
        _ <- logger.info[F](s"Fallback reconcile sent ${summary.upserts} upserts and ${summary.removals} removals")
      } yield summary
    }

  def run(interval: FiniteDuration = 24.hours, flagCheckInterval: FiniteDuration = 5.minutes): Stream[F, Unit] = {
    val scheduled = Stream.eval(reconcileSafely) ++ Stream.awakeEvery[F](interval).evalMap(_ => reconcileSafely)
    val flagged =
      Stream
        .awakeEvery[F](flagCheckInterval)
        .evalMap(_ => coordination.isReconcileNeeded.ifM(reconcileSafely, Async[F].unit))

    scheduled.merge(flagged)
  }

  private val reconcileSafely: F[Unit] =
    reconcile.void.handleErrorWith { error =>
      logger.error[F]("Fallback reconcile failed; it will be retried", error) *> coordination.markReconcileNeeded
    }

  /** Re-checks each candidate: paging through the DB while rows change can miss a video that still exists. */
  private def confirmedRemovals(videoIds: List[String], capturedAt: Instant): F[List[MainToFallbackMessage]] =
    videoIds.traverse { videoId =>
      transaction(fallbackSyncDao.findById(videoId)).map[MainToFallbackMessage] {
        case Some(syncedVideo) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
        case None => ScheduledVideoRemoval(videoId, capturedAt)
      }
    }
}
