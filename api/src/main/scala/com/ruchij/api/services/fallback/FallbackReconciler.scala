package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import cats.{Monad, ~>}
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, FallbackSyncTransport, ManifestEntry}
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration._

/** `withheldRemovals` counts removals the mass-removal guard refused to send. */
final case class ReconcileSummary(upserts: Int, removals: Int, withheldRemovals: Int = 0)

// Async[F], not just Temporal[F], because the failure path logs via `com.ruchij.core.logging.Logger`, which is
// Sync-based; Temporal and Sync are siblings in the cats-effect hierarchy (joined only by Async), so requiring both
// separately produces ambiguous implicits (see FallbackSyncPublisher).
class FallbackReconciler[F[_]: Async: Clock, T[_]: Monad](
  manifestReader: FallbackManifestReader[F],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  coordination: FallbackSyncCoordination[F],
  instanceId: String,
  allowMassRemoval: Boolean = false
)(implicit transaction: T ~> F) {
  import FallbackReconciler._

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
        _ <- warnAboutFutureCapturedAt(manifest, capturedAt)
        diff = ReconcileDiff.compute(manifest, videos.map(ScheduledVideoUpserts.from(_, capturedAt)))
        rechecked <- confirmedRemovals(diff.removedVideoIds)
        recheckedUpserts = rechecked.collect { case upsert: ScheduledVideoUpsert => upsert }
        removals = rechecked.collect { case removal: ScheduledVideoRemoval => removal }
        sendRemovals <- removalsAllowed(manifest.size, videos.isEmpty, removals.size)
        upserts = diff.upserts ++ recheckedUpserts
        _ <- transport.send(upserts ++ (if (sendRemovals) removals else Nil))
        summary = ReconcileSummary(
          upserts.size,
          if (sendRemovals) removals.size else 0,
          if (sendRemovals) 0 else removals.size
        )
        _ <- logger.info[F] {
          s"Fallback reconcile sent ${summary.upserts} upserts and ${summary.removals} removals" +
            (if (summary.withheldRemovals > 0) s", withholding ${summary.withheldRemovals} removals" else "")
        }
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

  /** Refuses a mass removal, which more likely means the main side read the wrong or an empty database than that
    * most videos were really deleted: the fallback would then lose its copy exactly when it may be needed. */
  private def removalsAllowed(manifestSize: Int, databaseEmpty: Boolean, removalCount: Int): F[Boolean] = {
    val refusal =
      if (removalCount == 0 || allowMassRemoval) None
      else if (databaseEmpty)
        Some(s"the database returned no videos while the fallback holds $manifestSize")
      else if (removalCount > maxRemovals(manifestSize))
        Some(s"$removalCount of the fallback's $manifestSize videos would be removed")
      else None

    refusal.fold(Async[F].pure(true)) { reason =>
      logger
        .error[F](
          "Withholding every fallback reconcile removal; upserts are still sent",
          new IllegalStateException(
            s"Refusing a mass removal: $reason. Set FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL=true for one run " +
              "if this is intended."
          )
        )
        .as(false)
    }
  }

  /** The fallback skips any change whose capturedAt is not newer than the stored one, so an item stamped ahead of
    * the database clock (e.g. by a clock that has since been corrected) ignores changes until the clock catches up. */
  private def warnAboutFutureCapturedAt(manifest: Map[String, ManifestEntry], databaseTime: Instant): F[Unit] = {
    val ahead = entriesAhead(manifest, databaseTime)

    logger
      .warn[F] {
        s"${ahead.size} fallback videos carry a capturedAt more than $FutureCapturedAtTolerance ahead of the " +
          s"database clock ($databaseTime), so the fallback ignores their changes until then: " +
          ahead.take(10).mkString(", ")
      }
      .whenA(ahead.nonEmpty)
  }

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

object FallbackReconciler {
  val FutureCapturedAtTolerance: FiniteDuration = 1.minute

  /** The most removals one reconcile sends without `allowMassRemoval`: 50, or 20% of the manifest if more. */
  def maxRemovals(manifestSize: Int): Int = math.max(50, manifestSize / 5)

  def entriesAhead(manifest: Map[String, ManifestEntry], databaseTime: Instant): List[String] =
    manifest.toList.collect {
      case (videoId, entry) if entry.capturedAt.isAfter(databaseTime.plusMillis(FutureCapturedAtTolerance.toMillis)) =>
        videoId
    }.sorted
}
