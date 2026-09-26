package com.ruchij.api.services.fallback

import cats.effect.{Async, Fiber, Ref}
import cats.implicits._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher

import scala.concurrent.duration._

/** Asks for a fallback sync of videos after a user-visible write. Never fails and never holds the request for more
  * than `gracePeriod`: a failed, slow or stuck publish (e.g. a Kafka producer blocked on a missing topic) is logged
  * and skipped, leaving the daily reconcile to repair the fallback.
  *
  * Each call publishes in its own background fiber and waits up to `gracePeriod` for it, so a healthy publish still
  * completes before the call returns. A stuck publish keeps running in the background -- fs2-kafka's send can be
  * uncancelable while it waits on metadata -- holding one of `maxInFlight` slots until it finishes or `publishTimeout`
  * cancels it. Once every slot is held, further calls skip publishing without waiting at all, so an outage costs a
  * bounded number of fibers and at most `gracePeriod` of latency per request, not a fixed timeout on every request.
  */
class FallbackSyncRequester[F[_]: Async](
  publisher: Publisher[F, FallbackSyncRequest],
  gracePeriod: FiniteDuration = 500.millis,
  publishTimeout: FiniteDuration = 30.seconds,
  maxInFlight: Int = 32
) {
  private val logger = Logger[FallbackSyncRequester[F]]

  // Created eagerly so the requester can be built outside F, like the services it is injected into
  private val inFlight: Ref[F, Int] = Ref.unsafe[F, Int](0)

  def request(videoId: String): F[Unit] = requestAll(List(videoId))

  /** Publishes a sync request for each video id, one after another in a single background fiber, isolating each id's
    * failure so later ids are still attempted. */
  def requestAll(videoIds: Seq[String]): F[Unit] =
    // Uncancelable so a slot, once taken, is always handed to a fiber that releases it
    Async[F]
      .uncancelable { _ =>
        tryAcquireSlot.ifM[Option[Fiber[F, Throwable, Unit]]](
          Async[F].start(publishInBackground(videoIds)).map(Option(_)),
          logger
            .warn[F](
              s"Skipping fallback sync request for videos ${videoIds.mkString(", ")}: $maxInFlight earlier requests " +
                "are still in flight"
            )
            .as(None)
        )
      }
      .flatMap {
        case Some(fiber) => Async[F].timeoutTo(fiber.join.void, gracePeriod, Async[F].unit)
        case None => Async[F].unit
      }

  private def publishInBackground(videoIds: Seq[String]): F[Unit] =
    Async[F].guarantee(
      Async[F].timeout(publishEach(videoIds), publishTimeout).handleErrorWith { error =>
        logger.warn[F](s"Fallback sync request for videos ${videoIds.mkString(", ")} timed out or failed: $error")
      },
      inFlight.update(_ - 1)
    )

  private val tryAcquireSlot: F[Boolean] =
    inFlight.modify(count => if (count < maxInFlight) (count + 1, true) else (count, false))

  private def publishEach(videoIds: Seq[String]): F[Unit] =
    videoIds.toList.traverse_ { id =>
      publisher.publishOne(FallbackSyncRequest(id)).handleErrorWith { error =>
        logger.warn[F](s"Unable to request a fallback sync for video $id: $error")
      }
    }
}
