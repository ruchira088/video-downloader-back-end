package com.ruchij.core.test

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object IOSupport {
  // Generous enough for tests that start containers, but turns a hung test into a failure instead of a stalled run
  val DefaultTestTimeout: FiniteDuration = 5.minutes

  // timeoutAndForget, not timeout: a test stuck in an uncancelable blocking call (e.g. a JDBC socket read) would
  // otherwise make the timeout wait forever for the cancellation to finish
  def runIO[A](block: => IO[A]): A =
    IO.defer(block).timeoutAndForget(DefaultTestTimeout).unsafeRunSync()

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
