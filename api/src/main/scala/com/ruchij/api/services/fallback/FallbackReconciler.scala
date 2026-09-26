package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import cats.{Monad, ~>}
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, FallbackSyncTransport}
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream

import scala.concurrent.duration._

final case class ReconcileSummary(upserts: Int, removals: Int)

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits (see FallbackSyncPublisher).
class FallbackReconciler[F[_]: Async: Clock, T[_]: Monad](
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
        // capturedAt comes from the database clock, in the same transaction as the read it stamps
        (capturedAt, videos) <- transaction(fallbackSyncDao.currentTimestamp.product(fallbackSyncDao.findAll))
        diff = ReconcileDiff.compute(manifest, videos.map(ScheduledVideoUpserts.from(_, capturedAt)))
        removals <- confirmedRemovals(diff.removedVideoIds)
        _ <- transport.send(diff.upserts ++ removals)
        summary = ReconcileSummary(diff.upserts.size, removals.count(_.isInstanceOf[ScheduledVideoRemoval]))
        _ <- logger.info[F](s"Fallback reconcile sent ${summary.upserts} upserts and ${summary.removals} removals")
        completedAt <- Clock[F].timestamp
        _ <- coordination.recordSuccessfulReconcile(completedAt)
      } yield summary
    }

  /** Reconciles at startup, then on every `interval` tick unless any instance completed a reconcile within
    * `dailySkipWindow` (so the instances share one daily run), and whenever a flag check finds the flag set. */
  def run(
    interval: FiniteDuration = 24.hours,
    flagCheckInterval: FiniteDuration = 5.minutes,
    dailySkipWindow: FiniteDuration = 20.hours
  ): Stream[F, Unit] = {
    val scheduled =
      Stream.eval(tick(reconcileSafely(retryWhenLocked = true))) ++
        Stream.awakeEvery[F](interval).evalMap(_ => tick(dailyReconcile(dailySkipWindow)))
    val flagged = Stream.awakeEvery[F](flagCheckInterval).evalMap(_ => flagCheckTick)

    scheduled.merge(flagged)
  }

  /** `retryWhenLocked` flags a retry when another holder has the lock: that holder may be a crashed instance whose
    * lock outlives it by up to its TTL, having already cleared the flag, so without it the startup or flagged run
    * would be lost. The flag is only checked every `flagCheckInterval`, so this retries at that pace, not in a loop.
    * The daily run doesn't need it: if another instance holds the lock, that instance is reconciling. */
  private def reconcileSafely(retryWhenLocked: Boolean): F[Unit] =
    reconcile
      .flatMap {
        case None if retryWhenLocked =>
          logger.info[F]("Another instance holds the fallback reconcile lock; flagging a retry") *>
            coordination.markReconcileNeeded

        case _ => Async[F].unit
      }
      .handleErrorWith { error =>
        logger.error[F]("Fallback reconcile failed; it will be retried", error) *> coordination.markReconcileNeeded
      }

  private def dailyReconcile(skipWindow: FiniteDuration): F[Unit] =
    (coordination.lastSuccessfulReconcile, Clock[F].timestamp).tupled.flatMap {
      case (Some(lastRun), now) if lastRun.isAfter(now.minusMillis(skipWindow.toMillis)) =>
        logger.info[F](s"Skipping the daily fallback reconcile: one completed at $lastRun")

      case _ => reconcileSafely(retryWhenLocked = false)
    }

  /** Every tick of the schedule must be fail-safe end to end: `Stream.merge` ends both the daily and flag-check
    * paths the moment either side's `evalMap` raises, so a single bad tick (e.g. the key-value store or transport
    * being down, which is the likely reason a reconcile failed in the first place) must never escape as an
    * exception, or the whole reconciler dies silently until the process restarts. */
  private def tick(run: F[Unit]): F[Unit] =
    run.handleErrorWith(error => logger.error[F]("Fallback reconcile tick failed unexpectedly", error))

  private val flagCheckTick: F[Unit] =
    coordination.isReconcileNeeded
      .ifM(reconcileSafely(retryWhenLocked = true), Async[F].unit)
      .handleErrorWith(error => logger.error[F]("Fallback reconcile flag check failed unexpectedly", error))

  /** Re-checks each candidate: paging through the DB while rows change can miss a video that still exists. Each
    * re-check takes a fresh timestamp in its own read's transaction: reusing the listing's older timestamp would let
    * a removal lose to, or be stamped before, a change made between the listing and the re-check. */
  private def confirmedRemovals(videoIds: List[String]): F[List[MainToFallbackMessage]] =
    videoIds.traverse { videoId =>
      transaction(fallbackSyncDao.currentTimestamp.product(fallbackSyncDao.findById(videoId)))
        .map[MainToFallbackMessage] {
          case (capturedAt, Some(syncedVideo)) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
          case (capturedAt, None) => ScheduledVideoRemoval(videoId, capturedAt)
        }
    }
}
