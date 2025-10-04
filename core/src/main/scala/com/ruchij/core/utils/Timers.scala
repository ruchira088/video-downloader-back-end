package com.ruchij.core.utils

import cats.Applicative
import cats.effect._
import cats.implicits._
import fs2.Stream

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

object Timers {

  def createResettableTimer[F[_]: Concurrent: Temporal](
    interval: FiniteDuration,
    resetSignal: Ref[F, Boolean]
  ): F[Either[Throwable, Unit]] =
    Concurrent[F]
      .race(
        Stream
          .fixedRate[F](5 seconds)
          .productR(Stream.eval(resetSignal.get))
          .filter(active => active)
          .take(1)
          .compile
          .lastOrError,
        Temporal[F].sleep(interval).as(false)
      )
      .map(_.fold[Boolean](identity[Boolean], identity[Boolean]))
      .flatMap { result =>
        if (result)
          resetSignal.set(false).productR(createResettableTimer(interval, resetSignal))
        else
         Applicative[F].pure {
           Left(new TimeoutException(s"Timeout occurred after ${interval.toMillis}ms in resettable timer"))
         }
      }

}
