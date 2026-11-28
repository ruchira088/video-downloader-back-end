package com.ruchij.core.types

import cats.arrow.FunctionK
import cats.data.{Kleisli, OptionT}
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, ~>}

object FunctionKTypes {
  extension [F[_, _], A, B](value: F[B, A]) {
    def toType[G[_], C >: B](using functionK: F[C, *] ~> G, functor: Functor[F[*, A]]): G[A] =
      functionK.apply(functor.map(value)(identity[C]))
  }

  extension [F[_], A](value: F[A]) {
    def toType[G[_], B](failure: => B)(using functionK: F ~> OptionT[G, *], monadError: MonadError[G, B]): G[A] =
      functionK.apply(value).getOrElseF(monadError.raiseError(failure))
  }

  given eitherToF[L, F[_]](using applicativeError: ApplicativeError[F, L]): (Either[L, *] ~> F) =
    new ~>[Either[L, *], F] {
      override def apply[A](either: Either[L, A]): F[A] =
        either.fold(ApplicativeError[F, L].raiseError, Applicative[F].pure)
    }

  given eitherLeftFunctor[R]: Functor[Either[*, R]] =
    new Functor[Either[*, R]] {
      override def map[A, B](fa: Either[A, R])(f: A => B): Either[B, R] = fa.left.map(f)
    }

  given identityFunctionK[F[_]]: ~>[F, F] = FunctionK.id[F]

  given optionToOptionT[F[_]: Applicative]: (Option ~> OptionT[F, *]) =
    new ~>[Option, OptionT[F, *]] {
      override def apply[A](option: Option[A]): OptionT[F, A] = OptionT.fromOption[F](option)
    }

  extension [F[_]: Monad, A, B](kleisli: Kleisli[F, A, Option[B]]) {
    def or(other: => Kleisli[F, A, B]): Kleisli[F, A, B] =
      kleisli.flatMap(_.fold(other)(value => Kleisli.pure[F, A, B](value)))
  }
}
