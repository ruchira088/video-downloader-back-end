package com.ruchij.core.types

import cats.effect.IO

import java.time.Instant

trait Clock[F[_]] {
  def timestamp: F[Instant]
}

object Clock {
  def apply[F[_]](implicit clock: Clock[F]): Clock[F] = clock

  implicit val clockIO: Clock[IO] = new Clock[IO] {
    override val timestamp: IO[Instant] = IO.delay(Instant.now())
  }
}
