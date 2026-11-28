package com.ruchij.core.kv.codecs

import cats.implicits._
import cats.{Applicative, Monad}
import com.ruchij.core.kv.keys.KVStoreKey.KeySeparator
import enumeratum.EnumEntry

import java.time.Instant

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

  implicit def enumKVEncoder[F[_]: Applicative, A <: EnumEntry]: KVEncoder[F, A] =
    stringKVEncoder[F].coMap[A](_.entryName)

  implicit def longKVEncoder[F[_]: Monad]: KVEncoder[F, Long] = stringKVEncoder[F].coMap[Long](_.toString)

  implicit def instantKVEncoder[F[_]: Applicative]: KVEncoder[F, Instant] =
    stringKVEncoder[F].coMap[Instant](_.toString)

  // A product is stored as the encodings of its fields, in order, joined with the key separator. KVDecoder.forProductN
  // reads it back.
  def forProduct2[F[_]: Applicative, A, B1, B2](fields: A => (B1, B2))(
    implicit encoder1: KVEncoder[F, B1],
    encoder2: KVEncoder[F, B2]
  ): KVEncoder[F, A] =
    (value: A) => {
      val (b1, b2) = fields(value)
      joinTerms(List(encoder1.encode(b1), encoder2.encode(b2)))
    }

  def forProduct3[F[_]: Applicative, A, B1, B2, B3](fields: A => (B1, B2, B3))(
    implicit encoder1: KVEncoder[F, B1],
    encoder2: KVEncoder[F, B2],
    encoder3: KVEncoder[F, B3]
  ): KVEncoder[F, A] =
    (value: A) => {
      val (b1, b2, b3) = fields(value)
      joinTerms(List(encoder1.encode(b1), encoder2.encode(b2), encoder3.encode(b3)))
    }

  def forProduct4[F[_]: Applicative, A, B1, B2, B3, B4](fields: A => (B1, B2, B3, B4))(
    implicit encoder1: KVEncoder[F, B1],
    encoder2: KVEncoder[F, B2],
    encoder3: KVEncoder[F, B3],
    encoder4: KVEncoder[F, B4]
  ): KVEncoder[F, A] =
    (value: A) => {
      val (b1, b2, b3, b4) = fields(value)
      joinTerms(List(encoder1.encode(b1), encoder2.encode(b2), encoder3.encode(b3), encoder4.encode(b4)))
    }

  def forProduct5[F[_]: Applicative, A, B1, B2, B3, B4, B5](fields: A => (B1, B2, B3, B4, B5))(
    implicit encoder1: KVEncoder[F, B1],
    encoder2: KVEncoder[F, B2],
    encoder3: KVEncoder[F, B3],
    encoder4: KVEncoder[F, B4],
    encoder5: KVEncoder[F, B5]
  ): KVEncoder[F, A] =
    (value: A) => {
      val (b1, b2, b3, b4, b5) = fields(value)
      joinTerms(
        List(
          encoder1.encode(b1),
          encoder2.encode(b2),
          encoder3.encode(b3),
          encoder4.encode(b4),
          encoder5.encode(b5)
        )
      )
    }

  def forProduct6[F[_]: Applicative, A, B1, B2, B3, B4, B5, B6](fields: A => (B1, B2, B3, B4, B5, B6))(
    implicit encoder1: KVEncoder[F, B1],
    encoder2: KVEncoder[F, B2],
    encoder3: KVEncoder[F, B3],
    encoder4: KVEncoder[F, B4],
    encoder5: KVEncoder[F, B5],
    encoder6: KVEncoder[F, B6]
  ): KVEncoder[F, A] =
    (value: A) => {
      val (b1, b2, b3, b4, b5, b6) = fields(value)
      joinTerms(
        List(
          encoder1.encode(b1),
          encoder2.encode(b2),
          encoder3.encode(b3),
          encoder4.encode(b4),
          encoder5.encode(b5),
          encoder6.encode(b6)
        )
      )
    }

  private def joinTerms[F[_]: Applicative](terms: List[F[String]]): F[String] =
    terms.sequence.map(_.mkString(KeySeparator))
}
