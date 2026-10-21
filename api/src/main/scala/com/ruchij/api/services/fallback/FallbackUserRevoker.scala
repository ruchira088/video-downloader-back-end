package com.ruchij.api.services.fallback

import cats.Applicative
import cats.effect.Async
import cats.implicits._
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.UserRevocation
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock

import scala.concurrent.duration._

/** Revokes a user's fallback account once their credentials changed (a password reset) or they were deleted, so the
  * fallback stops accepting their old password and tokens. Never fails. */
trait FallbackUserRevoker[F[_]] {
  def revoke(user: User): F[Unit]
}

object FallbackUserRevoker {
  /** Used while fallback sync is disabled. */
  def noOp[F[_]: Applicative]: FallbackUserRevoker[F] =
    new FallbackUserRevoker[F] {
      override def revoke(user: User): F[Unit] = Applicative[F].unit
    }
}

/** Sends a `UserRevocation` straight to the fallback, with the transport's own retries, waiting at most `timeout` so a
  * slow SQS can't hold up the reset or deletion for long. A revocation that can't be sent is logged and dropped: unlike
  * the scheduled videos, a reconcile can't repair it, so the user's old fallback password then keeps working until
  * their next sign-up to the fallback. */
class TransportFallbackUserRevoker[F[_]: Async: Clock](
  transport: FallbackSyncTransport[F],
  timeout: FiniteDuration = 10.seconds
) extends FallbackUserRevoker[F] {
  private val logger = Logger[TransportFallbackUserRevoker[F]]

  override def revoke(user: User): F[Unit] =
    Clock[F].timestamp
      .flatMap { capturedAt =>
        Async[F].timeout(transport.send(List(UserRevocation(user.id, user.email.value, capturedAt))), timeout)
      }
      .handleErrorWith { error =>
        logger.error[F](s"Unable to revoke the fallback account of user ${user.id}", error)
      }
}
