package com.ruchij.core.kv.codecs

import cats.implicits._
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, MonadThrow}
import com.ruchij.core.kv.keys.KVStoreKey.{KeyList, KeySeparator}
import enumeratum.{Enum, EnumEntry}
import org.joda.time.DateTime
import shapeless.{::, <:!<, Generic, HList, HNil}

import scala.reflect.ClassTag
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

  implicit def dateTimeKVDecoder[F[_]: MonadThrow]: KVDecoder[F, DateTime] =
    stringKVDecoder[F].mapEither { value =>
      Try(DateTime.parse(value)).toEither.left.map(_.getMessage)
    }

  implicit def genericKVDecoder[F[_]: MonadThrow, A, Repr <: HList](
    implicit generic: Generic.Aux[A, Repr],
    decoder: KVDecoder[F, Repr]
  ): KVDecoder[F, A] =
    (value: String) => decoder.decode(value).map(generic.from)

  implicit def reprKVDecoder[F[_]: MonadThrow, H, T <: HList](
    implicit headKVDecoder: KVDecoder[F, H],
    tailKVDecoder: KVDecoder[F, T],
    headTermCount: TermCount[H]
  ): KVDecoder[F, H :: T] = {
    case KeyList(keys @ _*) if keys.length >= headTermCount.size =>
      headKVDecoder.decode(keys.take(headTermCount.size).mkString(KeySeparator)).flatMap { value =>
        tailKVDecoder.decode(keys.drop(headTermCount.size).mkString(KeySeparator)).map(value :: _)
      }

    case _ => ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException("Key is too short"))
  }

  implicit def hNilKVDecoder[F[_]: ApplicativeError[*[_], Throwable]]: KVDecoder[F, HNil] =
    (value: String) =>
      if (value.trim.nonEmpty)
        ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException(s"Key contains extra terms: $value"))
      else Applicative[F].pure(HNil)

  sealed trait TermCount[A] {
    val size: Int
  }

  object TermCount {
    def apply[A](implicit termCount: TermCount[A]): TermCount[A] = termCount

    implicit def productTermCount[A <: Product, Repr <: HList: Generic.Aux[A, *]](
      implicit hlistTermCount: TermCount[Repr]
    ): TermCount[A] =
      new TermCount[A] {
        override val size: Int = hlistTermCount.size
      }

    implicit def nonProductTermCount[A: * <:!< Product]: TermCount[A] =
      new TermCount[A] {
        override val size: Int = 1
      }

    implicit def hlistTermCount[H, Tail <: HList](
      implicit headTermCount: TermCount[H],
      tailTermCount: TermCount[Tail]
    ): TermCount[H :: Tail] =
      new TermCount[H :: Tail] {
        override val size: Int = headTermCount.size + tailTermCount.size
      }

    implicit val hnilTermCount: TermCount[HNil] =
      new TermCount[HNil] {
        override val size: Int = 0
      }

  }

}
