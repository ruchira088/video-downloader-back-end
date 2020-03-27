package com.ruchij.types

import cats.effect.IO

trait UnsafeExtractor[M[_]] {
  def extract[A](value: M[A]): A
}

object UnsafeExtractor {
  implicit val unsafeIoExtractor: UnsafeExtractor[IO] =
    new UnsafeExtractor[IO] {
      override def extract[A](value: IO[A]): A = value.unsafeRunSync()
    }

  def extractUnsafely[A, M[_]](value: M[A])(implicit unsafeExtractor: UnsafeExtractor[M]): A =
    unsafeExtractor.extract(value)
}
