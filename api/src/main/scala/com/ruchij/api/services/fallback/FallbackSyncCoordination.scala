package com.ruchij.api.services.fallback

import cats.effect.MonadCancelThrow
import cats.implicits._
import com.ruchij.core.kv.KeyValueStore

import java.time.Instant
import scala.concurrent.duration._

class FallbackSyncCoordination[F[_]: MonadCancelThrow](
  keyValueStore: KeyValueStore[F],
  lockTtl: FiniteDuration = 30.minutes
) {
  import FallbackSyncCoordination._

  def markReconcileNeeded: F[Unit] = keyValueStore.put[String, String](ReconcileNeededKey, "true", None).void

  def isReconcileNeeded: F[Boolean] = keyValueStore.get[String, String](ReconcileNeededKey).map(_.nonEmpty)

  def clearReconcileNeeded: F[Unit] = keyValueStore.remove[String](ReconcileNeededKey).void

  /** When any instance last completed a reconcile, so only one of them runs the daily one. */
  def lastSuccessfulReconcile: F[Option[Instant]] = keyValueStore.get[String, Instant](LastSuccessfulReconcileKey)

  def recordSuccessfulReconcile(completedAt: Instant): F[Unit] =
    keyValueStore.put[String, Instant](LastSuccessfulReconcileKey, completedAt, Some(7.days)).void

  /**
    * Best-effort mutual exclusion: two instances can both acquire under a race, which is harmless because every
    * sync message is idempotent. It only avoids routinely doing the same work three times.
    */
  def withReconcileLock[A](owner: String)(fa: F[A]): F[Option[A]] =
    tryAcquire(owner).flatMap { acquired =>
      if (acquired) MonadCancelThrow[F].guarantee(fa, release(owner)).map(Option(_))
      else MonadCancelThrow[F].pure(Option.empty[A])
    }

  private def tryAcquire(owner: String): F[Boolean] =
    keyValueStore.get[String, String](ReconcileLockKey).flatMap {
      case Some(_) => MonadCancelThrow[F].pure(false)
      case None =>
        keyValueStore.put[String, String](ReconcileLockKey, owner, Some(lockTtl)) *>
          keyValueStore.get[String, String](ReconcileLockKey).map(_.contains(owner))
    }

  private def release(owner: String): F[Unit] =
    keyValueStore.get[String, String](ReconcileLockKey).flatMap { current =>
      keyValueStore.remove[String](ReconcileLockKey).void.whenA(current.contains(owner))
    }
}

object FallbackSyncCoordination {
  val ReconcileNeededKey = "fallback-sync::reconcile-needed"
  val ReconcileLockKey = "fallback-sync::reconcile-lock"
  val LastSuccessfulReconcileKey = "fallback-sync::last-successful-reconcile"
}
