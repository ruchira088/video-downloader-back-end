package com.ruchij.api.services.fallback

import cats.Applicative
import cats.effect.{Async, Fiber, Ref}
import cats.implicits._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Asks for a fallback sync of videos after a user-visible write. Never fails and never holds the caller for long. */
trait FallbackSyncRequester[F[_]] {
  def request(videoId: String): F[Unit]

  /** Requests a sync of each video id, isolating each id's failure so later ids are still attempted. */
  def requestAll(videoIds: Seq[String]): F[Unit]
}

object FallbackSyncRequester {
  /** Used while fallback sync is disabled: returns at once, without starting a fiber or a timer. */
  def noOp[F[_]: Applicative]: FallbackSyncRequester[F] =
    new FallbackSyncRequester[F] {
      override def request(videoId: String): F[Unit] = Applicative[F].unit

      override def requestAll(videoIds: Seq[String]): F[Unit] = Applicative[F].unit
    }
}

/** Publishes `FallbackSyncRequest`s. A failed, slow or stuck publish is logged and dropped, and every dropped request
  * flags a reconcile through `onDropped`, which repairs the fallback within minutes.
  *
  * Each call publishes in its own background fiber and waits up to `gracePeriod` for it, so a healthy publish still
  * completes before the call returns. A stuck publish keeps running in the background, holding one of `maxInFlight`
  * slots. A `requestAll` publishes its ids one after another in that one fiber, however long they take in all.
  * `publishTimeout` bounds each id's publish when it can be cancelled, but fs2-kafka's send is uncancelable while it
  * waits on topic metadata (up to the producer's `max.block.ms`, 60 s by default), so such a send keeps its slot
  * until it gives up by itself, however long `publishTimeout` is. Once every slot is held, calls skip publishing
  * without waiting at all.
  *
  * A publish still running at `publishTimeout` also opens a circuit breaker: for `degradedPeriod` afterwards, calls
  * return at once without starting a publish. An outage therefore costs a bounded number of fibers, and at most
  * `gracePeriod` of latency on the few requests made before the breaker opens.
  */
class PublishingFallbackSyncRequester[F[_]: Async](
  publisher: Publisher[F, FallbackSyncRequest],
  onDropped: F[Unit],
  gracePeriod: FiniteDuration = 500.millis,
  publishTimeout: FiniteDuration = 30.seconds,
  maxInFlight: Int = 32,
  degradedPeriod: FiniteDuration = 60.seconds,
  onDroppedTimeout: FiniteDuration = 10.seconds
) extends FallbackSyncRequester[F] {
  import PublishingFallbackSyncRequester._

  private val logger = Logger[PublishingFallbackSyncRequester[F]]

  // Created eagerly so the requester can be built outside F, like the services it is injected into
  private val inFlight: Ref[F, Int] = Ref.unsafe[F, Int](0)
  // A deadline on the monotonic clock, until which publishing is skipped
  private val degradedUntil: Ref[F, Option[FiniteDuration]] = Ref.unsafe[F, Option[FiniteDuration]](None)
  private val dropFlagState: Ref[F, DropFlagState] = Ref.unsafe[F, DropFlagState](DropFlagState.Idle)

  private[fallback] val inFlightCount: F[Int] = inFlight.get

  override def request(videoId: String): F[Unit] = requestAll(List(videoId))

  override def requestAll(videoIds: Seq[String]): F[Unit] =
    if (videoIds.isEmpty) Async[F].unit
    else
      isDegraded.ifM(
        flagDropped,
        // Uncancelable so a slot, once taken, is always handed to a fiber that releases it
        Async[F]
          .uncancelable { _ =>
            tryAcquireSlot.ifM[Option[Fiber[F, Throwable, Unit]]](
              Async[F].start(publishInBackground(videoIds)).map(Option(_)),
              logger
                .warn[F](
                  s"Skipping fallback sync request for videos ${videoIds.mkString(", ")}: $maxInFlight earlier " +
                    "requests are still in flight"
                )
                .productR(flagDropped)
                .as(None)
            )
          }
          .flatMap {
            case Some(fiber) => Async[F].timeoutTo(fiber.join.void, gracePeriod, Async[F].unit)
            case None => Async[F].unit
          }
      )

  private def publishInBackground(videoIds: Seq[String]): F[Unit] =
    Async[F].guarantee(
      publishEach(videoIds.toList)
        .flatMap(allPublished => flagDropped.unlessA(allPublished))
        .handleErrorWith { error =>
          logger.warn[F](s"Fallback sync request for videos ${videoIds.mkString(", ")} failed: $error") *>
            flagDropped
        },
      inFlight.update(_ - 1)
    )

  private val tryAcquireSlot: F[Boolean] =
    inFlight.modify(count => if (count < maxInFlight) (count + 1, true) else (count, false))

  /** True when every id was published. Each id's publish is timed on its own, so a requestAll of many ids (e.g. a
    * deleted user's videos) may run well past `publishTimeout` in its background fiber without opening the breaker.
    * Once one publish outlives `publishTimeout`, or the breaker is open, the remaining ids are dropped. */
  private def publishEach(videoIds: List[String]): F[Boolean] =
    videoIds match {
      case Nil => Async[F].pure(true)

      case id :: remaining =>
        isDegraded.flatMap { degraded =>
          if (degraded) dropRemaining(videoIds)
          else
            publishWithTimeout(id).flatMap {
              case PublishOutcome.Published => publishEach(remaining)
              case PublishOutcome.Failed => publishEach(remaining).as(false)
              case PublishOutcome.TimedOut => dropRemaining(remaining)
            }
        }
    }

  private def dropRemaining(videoIds: List[String]): F[Boolean] =
    logger
      .warn[F](s"Skipping fallback sync requests for videos ${videoIds.mkString(", ")}: publishing is degraded")
      .whenA(videoIds.nonEmpty)
      .as(false)

  private def publishWithTimeout(id: String): F[PublishOutcome] = {
    // Opens the breaker at publishTimeout even while an uncancelable send keeps the timeout below from returning
    val watchdog = Async[F].sleep(publishTimeout) *> enterDegraded

    Async[F]
      .background(watchdog)
      .surround(Async[F].timeout(publisher.publishOne(FallbackSyncRequest(id)), publishTimeout))
      .as[PublishOutcome](PublishOutcome.Published)
      .handleErrorWith { error =>
        logger.warn[F](s"Unable to request a fallback sync for video $id: $error") *> {
          error match {
            case _: TimeoutException => enterDegraded.as[PublishOutcome](PublishOutcome.TimedOut)
            case _ => Async[F].pure[PublishOutcome](PublishOutcome.Failed)
          }
        }
      }
  }

  private val isDegraded: F[Boolean] =
    Async[F].monotonic
      .flatMap { now =>
        degradedUntil.modify {
          case Some(until) if now < until => (Some(until), (true, false))
          case Some(_) => (None, (false, true))
          case None => (None, (false, false))
        }
      }
      .flatMap {
        case (degraded, leaving) =>
          logger.info[F]("Resuming fallback sync requests after the degraded period").whenA(leaving).as(degraded)
      }

  private val enterDegraded: F[Unit] =
    Async[F].monotonic
      .flatMap { now =>
        degradedUntil.modify { current =>
          (Some(now + degradedPeriod), current.forall(_ <= now))
        }
      }
      .flatMap { entering =>
        logger
          .warn[F] {
            s"A fallback sync request is still unpublished after $publishTimeout; skipping fallback sync requests " +
              s"for $degradedPeriod and flagging reconciles instead"
          }
          .whenA(entering)
      }

  /** Starts at most one fiber at a time to run `onDropped`, however many requests are dropped: drops made while it
    * runs are coalesced into one more run once it finishes, so every drop is followed by a completed `onDropped`. */
  private val flagDropped: F[Unit] =
    Async[F].uncancelable { _ =>
      dropFlagState
        .modify {
          case DropFlagState.Idle => (DropFlagState.Running, Async[F].start(runOnDropped).void)
          case _ => (DropFlagState.RunAgain, Async[F].unit)
        }
        .flatten
    }

  private lazy val runOnDropped: F[Unit] =
    Async[F].timeout(onDropped, onDroppedTimeout).handleErrorWith { error =>
      logger.warn[F](s"Unable to flag a fallback reconcile for a dropped sync request: $error")
    } *>
      dropFlagState.modify {
        case DropFlagState.RunAgain => (DropFlagState.Running, runOnDropped)
        case _ => (DropFlagState.Idle, Async[F].unit)
      }.flatten
}

object PublishingFallbackSyncRequester {
  private sealed trait PublishOutcome

  private object PublishOutcome {
    case object Published extends PublishOutcome
    case object Failed extends PublishOutcome
    case object TimedOut extends PublishOutcome
  }

  private sealed trait DropFlagState

  private object DropFlagState {
    case object Idle extends DropFlagState
    case object Running extends DropFlagState
    case object RunAgain extends DropFlagState
  }
}
