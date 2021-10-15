package com.ruchij.core.test

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.FiniteDuration

object IOSupport {
  def runIO[A](block: => IO[A]): A = block.unsafeRunSync()

  implicit class IOWrapper[A](value: IO[A]) {
    val error: IO[Throwable] =
      value.attempt.flatMap {
        case Left(throwable) => IO.pure(throwable)
        case Right(success) => IO.raiseError(new IllegalStateException(s"Expected an error, but returned $success"))
      }

    def withTimeout(finiteDuration: FiniteDuration): IO[A] =
      value.race(Providers.timeout[IO, A](finiteDuration)).map(_.merge)
  }
}
