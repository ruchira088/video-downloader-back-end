package com.ruchij.core.utils

import cats.Applicative
import cats.effect.{Ref, Temporal}
import cats.implicits._
import fs2.Stream

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Timers {
  private val ResetPollInterval: FiniteDuration = 5.seconds

  /**
    * Completes with a `Left(TimeoutException)` once `interval` elapses without `resetSignal` having been set to
    * `true`. Every observed reset clears the signal and restarts the interval. The timeout is returned as a value
    * (never raised) so the result can be passed straight to `Stream#interruptWhen`.
    */
  def createResettableTimer[F[_]: Temporal](
    interval: FiniteDuration,
    resetSignal: Ref[F, Boolean]
  ): F[Either[Throwable, Unit]] =
    Temporal[F]
      .race(
        Stream
          .fixedRate[F](ResetPollInterval)
          .evalMap(_ => resetSignal.get)
          .filter(identity)
          .take(1)
          .compile
          .drain,
        Temporal[F].sleep(interval)
      )
      .flatMap {
        case Left(_) => resetSignal.set(false).productR(createResettableTimer(interval, resetSignal))

        case Right(_) =>
          Applicative[F].pure {
            Left(new TimeoutException(s"Timeout occurred after ${interval.toMillis}ms in resettable timer"))
          }
      }
}
