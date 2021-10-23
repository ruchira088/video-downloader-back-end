package com.ruchij.core.test

import cats.{Applicative, ApplicativeError}
import cats.effect.kernel.Temporal
import cats.implicits._
import com.ruchij.core.types.JodaClock
import org.joda.time.DateTime

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

object Providers {
  def stubClock[F[_]: Applicative](dateTime: => DateTime): JodaClock[F] =
    new JodaClock[F] {
      override def timestamp: F[DateTime] = Applicative[F].pure(dateTime)
    }

  def timeout[F[_]: Temporal, A](finiteDuration: FiniteDuration): F[A] =
    Temporal[F].sleep(finiteDuration).productR {
      ApplicativeError[F, Throwable].raiseError {
        new TimeoutException(s"Timeout occurred after $finiteDuration")
      }
    }
}
