package com.ruchij.types

import cats.effect.IO
import cats.~>

object FunctionKTypes {
  implicit val throwableEitherToIo: Either[Throwable, *] ~> IO =
    new ~>[Either[Throwable, *], IO] {
      override def apply[A](value: Either[Throwable, A]): IO[A] = IO.fromEither(value)
    }

  def identityFuctionK[F[_]]: F ~> F = new ~>[F, F] {
    override def apply[A](fa: F[A]): F[A] = fa
  }
}
