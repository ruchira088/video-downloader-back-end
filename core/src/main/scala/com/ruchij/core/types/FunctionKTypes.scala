package com.ruchij.core.types

import cats.arrow.FunctionK
import cats.data.{Kleisli, OptionT}
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, ~>}

object FunctionKTypes {
  implicit class FunctionK2TypeOps[F[_, _], A, B](value: F[B, A]) {
    def toType[G[_], C >: B](implicit functionK: F[C, *] ~> G, functor: Functor[F[*, A]]): G[A] =
      functionK.apply(functor.map(value)(identity[C]))
  }

  implicit class FunctionKTypeOps[F[_], A](value: F[A]) {
    def toType[G[_], B](failure: => B)(implicit functionK: F ~> OptionT[G, *], monadError: MonadError[G, B]): G[A] =
      functionK.apply(value).getOrElseF(monadError.raiseError(failure))
  }

  implicit def eitherToF[L, F[_]: ApplicativeError[*[_], L]]: Either[L, *] ~> F =
    new ~>[Either[L, *], F] {
      override def apply[A](either: Either[L, A]): F[A] =
        either.fold(ApplicativeError[F, L].raiseError, Applicative[F].pure)
    }

  implicit def eitherLeftFunctor[R]: Functor[Either[*, R]] =
    new Functor[Either[*, R]] {
      override def map[A, B](fa: Either[A, R])(f: A => B): Either[B, R] = fa.left.map(f)
    }

  implicit def identityFunctionK[F[_]]: ~>[F, F] = FunctionK.id[F]

  implicit def optionToOptionT[F[_]: Applicative]: Option ~> OptionT[F, *] =
    new ~>[Option, OptionT[F, *]] {
      override def apply[A](option: Option[A]): OptionT[F, A] = OptionT.fromOption[F](option)
    }

  implicit class KleisliOption[F[_]: Monad, A, B](kleisli: Kleisli[F, A, Option[B]]) {
    def or(other: => Kleisli[F, A, B]): Kleisli[F, A, B] =
      kleisli.flatMap(_.fold(other)(value => Kleisli.pure[F, A, B](value)))
  }
}
