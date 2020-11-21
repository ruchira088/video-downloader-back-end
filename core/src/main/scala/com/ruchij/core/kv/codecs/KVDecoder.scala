package com.ruchij.core.kv.codecs

import cats.implicits._
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError}
import com.ruchij.core.kv.keys.KVStoreKey
import com.ruchij.core.kv.keys.KVStoreKey.{KeyList, KeySeparator}
import org.joda.time.DateTime
import shapeless.{::, <:!<, Generic, HList, HNil}

import scala.util.Try


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

  implicit def dateTimeKVDecoder[F[_]: MonadError[*[_], Throwable]]: KVDecoder[F, DateTime] =
    stringKVDecoder[F].mapEither { value =>
      Try(DateTime.parse(value)).toEither.left.map(_.getMessage)
    }

  implicit def genericKVDecoder[F[_]: MonadError[*[_], Throwable], A: * <:!< KVStoreKey, Repr <: HList](
    implicit generic: Generic.Aux[A, Repr],
    decoder: KVDecoder[F, Repr]
  ): KVDecoder[F, A] =
    (value: String) => decoder.decode(value).map(generic.from)

  implicit def reprKVDecoder[F[_]: MonadError[*[_], Throwable], H, T <: HList](
    implicit headKVDecoder: KVDecoder[F, H],
    tailKVDecoder: KVDecoder[F, T]
  ): KVDecoder[F, H :: T] = {
    case KeyList(head, tail @ _*) =>
      headKVDecoder.decode(head).flatMap { value =>
        tailKVDecoder.decode(tail.mkString(KeySeparator)).map(value :: _)
      }

    case _ => ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException("Key is too short"))
  }

  implicit def hNilKVDecoder[F[_]: ApplicativeError[*[_], Throwable]]: KVDecoder[F, HNil] =
    (value: String) =>
      if (value.trim.nonEmpty)
        ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException(s"Key contains extra terms: $value"))
      else Applicative[F].pure(HNil)

}
