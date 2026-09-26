package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher

import scala.concurrent.duration._

/** Asks for a fallback sync of a video after a user-visible write. Never fails and never blocks the request for
  * long: a failed or slow publish (e.g. a Kafka producer waiting on a missing topic) is logged and skipped, leaving
  * the daily reconcile to repair the fallback.
  *
  * Both `request` and `requestAll` use `timeoutAndForget`, not `timeout`: the underlying publish (e.g. fs2-kafka's
  * `producer.send`, which runs inside an uncancelable blocking call while waiting on metadata) may not respond to
  * cancellation, and `timeout` would block the caller until that cancellation completes. `timeoutAndForget` instead
  * returns as soon as the timer fires and lets the cancellation finish in the background. */
class FallbackSyncRequester[F[_]: Async](
  publisher: Publisher[F, FallbackSyncRequest],
  timeout: FiniteDuration = 5.seconds
) {
  private val logger = Logger[FallbackSyncRequester[F]]

  def request(videoId: String): F[Unit] =
    Async[F]
      .timeoutAndForget(publisher.publishOne(FallbackSyncRequest(videoId)), timeout)
      .handleErrorWith { error =>
        logger.warn[F](s"Unable to request a fallback sync for video $videoId: $error")
      }

  /** Publishes a sync request for each video id, one after another, all bounded by a single overall `timeout`
    * instead of one timeout per id -- so a stuck publisher adds at most one `timeout` to the caller, not
    * `videoIds.size * timeout`. Each id's publish is isolated: an error publishing one id (as opposed to the whole
    * batch timing out) is logged and skipped so later ids are still attempted. Any leftover failure or the overall
    * timeout is logged (warn) and swallowed. */
  def requestAll(videoIds: Seq[String]): F[Unit] =
    Async[F]
      .timeoutAndForget(
        videoIds.toList.traverse_ { id =>
          publisher.publishOne(FallbackSyncRequest(id)).handleErrorWith { error =>
            logger.warn[F](s"Unable to request a fallback sync for video $id: $error")
          }
        },
        timeout
      )
      .handleErrorWith { error =>
        logger.warn[F](s"Fallback sync request batch for videos ${videoIds.mkString(", ")} timed out or failed: $error")
      }
}
