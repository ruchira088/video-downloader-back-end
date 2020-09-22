package com.ruchij.kv.codecs

import cats.{Applicative, ApplicativeError, Monad, MonadError}
import cats.implicits._
import com.ruchij.exceptions.InvalidConditionException
import com.ruchij.kv.keys.KVStoreKey.KeySeparator
import org.joda.time.DateTime
import shapeless.{::, Generic, HList, HNil}

trait KVEncoder[F[_], -A] { self =>
  def encode(value: A): F[String]

  def coMap[B](f: B => A): KVEncoder[F, B] =
    (value: B) => self.encode(f(value))

  def coMapF[B, C <: A](f: B => F[C])(implicit monad: Monad[F]): KVEncoder[F, B] =
    (value: B) => f(value).flatMap(self.encode)
}

object KVEncoder {
  def apply[F[_], A](implicit kvEncoder: KVEncoder[F, A]): KVEncoder[F, A] = kvEncoder

  implicit def stringKVEncoder[F[_]: Applicative]: KVEncoder[F, String] =
    (value: String) => Applicative[F].pure(value)

  implicit def longKVEncoder[F[_]: Monad]: KVEncoder[F, Long] = stringKVEncoder[F].coMap[Long](_.toString)

  implicit def dateTimeKVEncoder[F[_]: Applicative]: KVEncoder[F, DateTime] =
    stringKVEncoder[F].coMap[DateTime](_.toString)

  implicit def genericKVEncoder[F[_]: Applicative, A, Repr](
    implicit generic: Generic.Aux[A, Repr],
    encoder: KVEncoder[F, Repr]
  ): KVEncoder[F, A] =
    (kvStoreKey: A) => encoder.encode(generic.to(kvStoreKey))

  implicit def reprEncoder[F[_]: MonadError[*[_], Throwable], H, T <: HList](
    implicit headEncoder: KVEncoder[F, H],
    tailEncoder: KVEncoder[F, T]
  ): KVEncoder[F, H :: T] = {
    case head :: HNil => headEncoder.encode(head)

    case head :: tail =>
      headEncoder
        .encode(head)
        .flatMap { value =>
          tailEncoder.encode(tail).map(value + KeySeparator + _)
        }
  }

  implicit def hNilKVEncoder[F[_]: ApplicativeError[*[_], Throwable]]: KVEncoder[F, HNil] =
    (_: HNil) => ApplicativeError[F, Throwable].raiseError(InvalidConditionException)
}
