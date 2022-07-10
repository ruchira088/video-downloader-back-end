package com.ruchij.core.types

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.{Applicative, ApplicativeError, Monad, ~>}

object FunctionKTypes {
  implicit class FunctionK2TypeOps[F[+ _, _], A, B](value: F[B, A]) {
    def toType[G[_], C >: B](implicit functionK: F[C, *] ~> G): G[A] = functionK.apply(value)
  }

  implicit def eitherToF[L, F[_]: ApplicativeError[*[_], L]]: Either[L, *] ~> F =
    new ~>[Either[L, *], F] {
      override def apply[A](either: Either[L, A]): F[A] =
        either.fold(ApplicativeError[F, L].raiseError, Applicative[F].pure)
    }

  implicit def identityFunctionK[F[_]]: ~>[F, F] = FunctionK.id[F]

  implicit class KleisliOption[F[_]: Monad, A, B](kleisli: Kleisli[F, A, Option[B]]) {
    def or(other: => Kleisli[F, A, B]): Kleisli[F, A, B] =
      kleisli.flatMap(_.fold(other)(value => Kleisli.pure[F, A, B](value)))
  }
}
