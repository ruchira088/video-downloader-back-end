package com.ruchij.kv.codecs

import cats.{Applicative, Monad}
import cats.implicits._

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
}
