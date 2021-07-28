package com.ruchij.core.test

import cats.effect.IO

object IOSupport {
  def runIO[A](block: => IO[A]): A = block.unsafeRunSync()

  implicit class IOWrapper(value: IO[_]) {
    val error: IO[Throwable] =
      value.attempt.flatMap {
        case Left(throwable) => IO.pure(throwable)
        case Right(success) => IO.raiseError(new IllegalStateException(s"Expected an error, but returned $success"))
      }
  }
}
