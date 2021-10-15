package com.ruchij.core.types

import cats.effect.IO
import org.joda.time.DateTime

trait JodaClock[F[_]] {
  def timestamp: F[DateTime]
}

object JodaClock {
  def apply[F[_]](implicit jodaClock: JodaClock[F]): JodaClock[F] = jodaClock

  implicit val jodaClockIO: JodaClock[IO] = new JodaClock[IO] {
    override val timestamp: IO[DateTime] = IO.delay(new DateTime())
  }
}
