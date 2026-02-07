package com.ruchij.core.test

import cats.{Applicative, ApplicativeError}
import cats.effect.kernel.Temporal
import cats.implicits._
import com.ruchij.core.types.Clock

import java.time.Instant
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

object Providers {
  def stubClock[F[_]: Applicative](instant: => Instant): Clock[F] =
    new Clock[F] {
      override def timestamp: F[Instant] = Applicative[F].pure(instant)
    }

  def timeout[F[_]: Temporal, A](finiteDuration: FiniteDuration): F[A] =
    Temporal[F].sleep(finiteDuration).productR {
      ApplicativeError[F, Throwable].raiseError {
        new TimeoutException(s"Timeout occurred after $finiteDuration")
      }
    }
}
