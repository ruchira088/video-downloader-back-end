package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher

import scala.concurrent.duration._

/** Asks for a fallback sync of a video after a user-visible write. Never fails and never blocks the request for
  * long: a failed or slow publish (e.g. a Kafka producer waiting on a missing topic) is logged and skipped, leaving
  * the daily reconcile to repair the fallback. */
class FallbackSyncRequester[F[_]: Async](
  publisher: Publisher[F, FallbackSyncRequest],
  timeout: FiniteDuration = 5.seconds
) {
  private val logger = Logger[FallbackSyncRequester[F]]

  def request(videoId: String): F[Unit] =
    Async[F]
      .timeout(publisher.publishOne(FallbackSyncRequest(videoId)), timeout)
      .handleErrorWith { error =>
        logger.warn[F](s"Unable to request a fallback sync for video $videoId: $error")
      }
}
