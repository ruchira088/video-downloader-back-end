package com.ruchij.kv.codecs

import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError}
import cats.implicits._

trait KVDecoder[F[_], A] { self =>
  def decode(value: String): F[A]

  def map[B](f: A => B)(implicit functor: Functor[F]): KVDecoder[F, B] =
    (value: String) => self.decode(value).map(f)

  def mapF[B](f: A => F[B])(implicit monad: Monad[F]): KVDecoder[F, B] =
    (value: String) => self.decode(value).flatMap(f)

  def mapEither[B](f: A => Either[String, B])(implicit monadError: MonadError[F, Throwable]): KVDecoder[F, B] =
    (value: String) =>
      self
        .decode(value)
        .flatMap { value =>
          f(value)
            .fold[F[B]](
              error => ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException(error)),
              result => Applicative[F].pure(result)
            )
      }
}

object KVDecoder {
  def apply[F[_], A](implicit kvDecoder: KVDecoder[F, A]): KVDecoder[F, A] = kvDecoder

  implicit def stringKVDecoder[F[_]: Applicative]: KVDecoder[F, String] =
    (value: String) => Applicative[F].pure(value)

  implicit def longKVDecoder[F[_]: MonadError[*[_], Throwable]]: KVDecoder[F, Long] =
    stringKVDecoder[F].mapEither { value =>
      value.toLongOption.fold[Either[String, Long]](Left(s"""Unable to parse "$value" as a Long"""))(Right.apply)
    }
}
