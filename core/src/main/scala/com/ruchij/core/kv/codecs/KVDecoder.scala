package com.ruchij.core.kv.codecs

import cats.implicits._
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, MonadThrow}
import com.ruchij.core.kv.keys.KVStoreKey.{KeyList, KeySeparator}
import enumeratum.{Enum, EnumEntry}

import java.time.Instant

import scala.reflect.ClassTag
import scala.util.Try

trait KVDecoder[F[_], A] { self =>
  def decode(value: String): F[A]

  // The number of key separated terms the encoded value consists of: one for a plain value, and the sum of its fields'
  // term counts for a product
  def termCount: Int = 1

  def map[B](f: A => B)(implicit functor: Functor[F]): KVDecoder[F, B] =
    withTermCount(self.termCount)(value => self.decode(value).map(f))

  def mapF[B](f: A => F[B])(implicit monad: Monad[F]): KVDecoder[F, B] =
    withTermCount(self.termCount)(value => self.decode(value).flatMap(f))

  def mapEither[B](f: A => Either[String, B])(implicit monadError: MonadError[F, Throwable]): KVDecoder[F, B] =
    withTermCount(self.termCount) { value =>
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

  private def withTermCount[B](count: Int)(decoder: String => F[B]): KVDecoder[F, B] =
    new KVDecoder[F, B] {
      override val termCount: Int = count

      override def decode(value: String): F[B] = decoder(value)
    }
}

object KVDecoder {
  def apply[F[_], A](implicit kvDecoder: KVDecoder[F, A]): KVDecoder[F, A] = kvDecoder

  implicit def stringKVDecoder[F[_]: Applicative]: KVDecoder[F, String] =
    (value: String) => Applicative[F].pure(value)

  implicit def enumKVDecoder[F[_]: MonadThrow, A <: EnumEntry](implicit enumValues: Enum[A]): KVDecoder[F, A] =
    stringKVDecoder[F].mapEither {
      string => enumValues.withNameInsensitiveEither(string).left.map(_.getMessage)
    }

  implicit def numericKVDecoder[F[_]: MonadThrow, A: Numeric](
    implicit classTag: ClassTag[A]
  ): KVDecoder[F, A] =
    stringKVDecoder[F].mapEither { value =>
      Numeric[A]
        .parseString(value)
        .fold[Either[String, A]](Left(s"""Unable to parse "$value" as a ${classTag.runtimeClass.getSimpleName}"""))(
          Right.apply
        )
    }

  implicit def instantKVDecoder[F[_]: MonadThrow]: KVDecoder[F, Instant] =
    stringKVDecoder[F].mapEither { value =>
      Try(Instant.parse(value)).toEither.left.map(_.getMessage)
    }

  // Reads a product written by KVEncoder.forProductN: the key separated terms are split among the fields by their term
  // counts, so a field may itself be a product
  def forProduct2[F[_]: MonadThrow, A, B1, B2](construct: (B1, B2) => A)(
    implicit decoder1: KVDecoder[F, B1],
    decoder2: KVDecoder[F, B2]
  ): KVDecoder[F, A] =
    product[F, A](List(decoder1.termCount, decoder2.termCount)) { fields =>
      (decoder1.decode(fields(0)), decoder2.decode(fields(1))).mapN(construct)
    }

  def forProduct3[F[_]: MonadThrow, A, B1, B2, B3](construct: (B1, B2, B3) => A)(
    implicit decoder1: KVDecoder[F, B1],
    decoder2: KVDecoder[F, B2],
    decoder3: KVDecoder[F, B3]
  ): KVDecoder[F, A] =
    product[F, A](List(decoder1.termCount, decoder2.termCount, decoder3.termCount)) { fields =>
      (decoder1.decode(fields(0)), decoder2.decode(fields(1)), decoder3.decode(fields(2))).mapN(construct)
    }

  def forProduct4[F[_]: MonadThrow, A, B1, B2, B3, B4](construct: (B1, B2, B3, B4) => A)(
    implicit decoder1: KVDecoder[F, B1],
    decoder2: KVDecoder[F, B2],
    decoder3: KVDecoder[F, B3],
    decoder4: KVDecoder[F, B4]
  ): KVDecoder[F, A] =
    product[F, A](List(decoder1.termCount, decoder2.termCount, decoder3.termCount, decoder4.termCount)) { fields =>
      (
        decoder1.decode(fields(0)),
        decoder2.decode(fields(1)),
        decoder3.decode(fields(2)),
        decoder4.decode(fields(3))
      ).mapN(construct)
    }

  def forProduct5[F[_]: MonadThrow, A, B1, B2, B3, B4, B5](construct: (B1, B2, B3, B4, B5) => A)(
    implicit decoder1: KVDecoder[F, B1],
    decoder2: KVDecoder[F, B2],
    decoder3: KVDecoder[F, B3],
    decoder4: KVDecoder[F, B4],
    decoder5: KVDecoder[F, B5]
  ): KVDecoder[F, A] =
    product[F, A](
      List(
        decoder1.termCount,
        decoder2.termCount,
        decoder3.termCount,
        decoder4.termCount,
        decoder5.termCount
      )
    ) { fields =>
      (
        decoder1.decode(fields(0)),
        decoder2.decode(fields(1)),
        decoder3.decode(fields(2)),
        decoder4.decode(fields(3)),
        decoder5.decode(fields(4))
      ).mapN(construct)
    }

  def forProduct6[F[_]: MonadThrow, A, B1, B2, B3, B4, B5, B6](construct: (B1, B2, B3, B4, B5, B6) => A)(
    implicit decoder1: KVDecoder[F, B1],
    decoder2: KVDecoder[F, B2],
    decoder3: KVDecoder[F, B3],
    decoder4: KVDecoder[F, B4],
    decoder5: KVDecoder[F, B5],
    decoder6: KVDecoder[F, B6]
  ): KVDecoder[F, A] =
    product[F, A](
      List(
        decoder1.termCount,
        decoder2.termCount,
        decoder3.termCount,
        decoder4.termCount,
        decoder5.termCount,
        decoder6.termCount
      )
    ) { fields =>
      (
        decoder1.decode(fields(0)),
        decoder2.decode(fields(1)),
        decoder3.decode(fields(2)),
        decoder4.decode(fields(3)),
        decoder5.decode(fields(4)),
        decoder6.decode(fields(5))
      ).mapN(construct)
    }

  private def product[F[_]: MonadThrow, A](
    termCounts: List[Int]
  )(decodeFields: Vector[String] => F[A]): KVDecoder[F, A] =
    new KVDecoder[F, A] {
      override val termCount: Int = termCounts.sum

      override def decode(value: String): F[A] = {
        val terms = KeyList.unapplySeq(value).value

        if (terms.length < termCount)
          ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException("Key is too short"))
        else if (terms.length > termCount)
          ApplicativeError[F, Throwable].raiseError {
            new IllegalArgumentException(s"Key contains extra terms: ${terms.drop(termCount).mkString(KeySeparator)}")
          }
        else
          decodeFields {
            termCounts
              .foldLeft((Vector.empty[String], terms)) { case ((fields, remaining), count) =>
                (fields :+ remaining.take(count).mkString(KeySeparator), remaining.drop(count))
              }
              ._1
          }
      }
    }
}
