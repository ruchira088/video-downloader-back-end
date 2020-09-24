package com.ruchij.kv.keys

import cats.implicits._
import cats.{ApplicativeError, Monad, MonadError}
import com.ruchij.kv.codecs.{KVDecoder, KVEncoder}
import org.joda.time.DateTime
import shapeless.{Generic, HList}

sealed trait KVStoreKey[A <: KVStoreKey[A]]

object KVStoreKey {
  val KeySeparator: String = "::"

  object KeyList {
    def unapplySeq(key: String): Some[Seq[String]] =
      if (key.trim.isEmpty) Some(Nil) else Some(key.split(KeySeparator).toList)
  }

  case class DownloadProgressKey(videoId: String) extends KVStoreKey[DownloadProgressKey]

  case class HealthCheckKey(dateTime: DateTime) extends KVStoreKey[HealthCheckKey]

  implicit def kvStoreKeyEncoder[F[_]: Monad, A <: KVStoreKey[A], Repr](
    implicit generic: Generic.Aux[A, Repr],
    encoder: KVEncoder[F, Repr],
    keySpace: KeySpace[A, _]
  ): KVEncoder[F, A] =
    KVEncoder[F, String].coMapF[A, String] {
      value => encoder.encode(generic.to(value)).map(keySpace.name + KeySeparator + _)
    }

  implicit def kvStoreKeyDecoder[F[_]: MonadError[*[_], Throwable], A <: KVStoreKey[A], Repr <: HList](
    implicit generic: Generic.Aux[A, Repr],
    kvDecoder: KVDecoder[F, Repr],
    keySpace: KeySpace[A, _]
  ): KVDecoder[F, A] = {
    case KeyList(KeySpace(`keySpace`), keys @ _*) =>
      kvDecoder.decode(keys.mkString(KeySeparator)).map(generic.from)

    case value =>
      ApplicativeError[F, Throwable].raiseError(
        new IllegalArgumentException(s"""Unable to parse "$value" as a ${classOf[KVStoreKey[_]].getSimpleName}""")
      )
  }
}
