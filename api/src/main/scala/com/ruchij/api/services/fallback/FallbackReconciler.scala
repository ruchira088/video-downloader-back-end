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
        // Clear the flag immediately after acquiring the lock, before reading anything: a flag raised by the
        // publisher mid-run (for a change that happens after this run's reads) must survive to trigger a later
        // reconcile. Clearing it only at the end would wipe out that later flag along with this run's own. The
        // failure path (reconcileSafely) re-marks the flag if this run itself fails.
        _ <- coordination.clearReconcileNeeded
        // The manifest must be read before the DB: a video synced between the two reads then shows up as an
        // extra (harmless) upsert instead of being wrongly removed.
        manifest <- manifestReader.manifest
        capturedAt <- Clock[F].timestamp
        videos <- transaction(fallbackSyncDao.findAll)
        diff = ReconcileDiff.compute(manifest, videos.map(ScheduledVideoUpserts.from(_, capturedAt)))
        removals <- confirmedRemovals(diff.removedVideoIds, capturedAt)
        _ <- transport.send(diff.upserts ++ removals)
        summary = ReconcileSummary(diff.upserts.size, removals.count(_.isInstanceOf[ScheduledVideoRemoval]))
        _ <- logger.info[F](s"Fallback reconcile sent ${summary.upserts} upserts and ${summary.removals} removals")
      } yield summary
    }

  def run(interval: FiniteDuration = 24.hours, flagCheckInterval: FiniteDuration = 5.minutes): Stream[F, Unit] = {
    val scheduled = Stream.eval(tick) ++ Stream.awakeEvery[F](interval).evalMap(_ => tick)
    val flagged = Stream.awakeEvery[F](flagCheckInterval).evalMap(_ => flagCheckTick)

    scheduled.merge(flagged)
  }

  private val reconcileSafely: F[Unit] =
    reconcile.void.handleErrorWith { error =>
      logger.error[F]("Fallback reconcile failed; it will be retried", error) *> coordination.markReconcileNeeded
    }

  /** Every tick of the schedule must be fail-safe end to end: `Stream.merge` ends both the daily and flag-check
    * paths the moment either side's `evalMap` raises, so a single bad tick (e.g. the key-value store or transport
    * being down, which is the likely reason a reconcile failed in the first place) must never escape as an
    * exception, or the whole reconciler dies silently until the process restarts. */
  private val tick: F[Unit] =
    reconcileSafely.handleErrorWith(error => logger.error[F]("Fallback reconcile tick failed unexpectedly", error))

  private val flagCheckTick: F[Unit] =
    coordination.isReconcileNeeded.ifM(reconcileSafely, Async[F].unit).handleErrorWith { error =>
      logger.error[F]("Fallback reconcile flag check failed unexpectedly", error)
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
