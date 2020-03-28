package com.ruchij.types

import cats.{Applicative, MonadError, ~>}

object FunctionKTypes {
  def identityFunctionK[F[_]]: F ~> F = new ~>[F, F] {
    override def apply[A](fa: F[A]): F[A] = fa
  }

  implicit def eitherToF[L, F[_]: MonadError[*[_], L]]: Either[L, *] ~> F =
    new ~>[Either[L, *], F] {
      override def apply[A](either: Either[L, A]): F[A] =
        either.fold(MonadError[F, L].raiseError, Applicative[F].pure)
    }
}
