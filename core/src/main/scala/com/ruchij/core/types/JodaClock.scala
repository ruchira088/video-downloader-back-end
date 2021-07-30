package com.ruchij.core.types

import java.util.concurrent.TimeUnit
import cats.Functor
import cats.effect.{Clock, Sync}
import cats.implicits.toFunctorOps
import org.joda.time.DateTime

trait JodaClock[F[_]] {
  val timestamp: F[DateTime]
}

object JodaClock {
  def apply[F[_]: Functor: Clock]: JodaClock[F] =
    new JodaClock[F] {
      override val timestamp: F[DateTime] =
        Clock[F].realTime(TimeUnit.MILLISECONDS).map(milliseconds => new DateTime(milliseconds))
    }

  def create[F[_]: Sync]: JodaClock[F] = JodaClock.apply(Functor[F], Clock.create[F])
}
