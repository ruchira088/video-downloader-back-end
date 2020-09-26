package com.ruchij.core.kv.keys

import cats.{ApplicativeError, Monad, MonadError}
import cats.implicits._
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import shapeless.{Generic, HList}

trait KVStoreKey

object KVStoreKey {
  val KeySeparator: String = "::"

  object KeyList {
    def unapplySeq(key: String): Some[Seq[String]] =
      if (key.trim.isEmpty) Some(Nil) else Some(key.split(KeySeparator).toList)
  }

  implicit def kvStoreKeyEncoder[F[_]: Monad, K <: KVStoreKey, Repr <: HList, V](
    implicit generic: Generic.Aux[K, Repr],
    encoder: KVEncoder[F, Repr],
    keySpace: KeySpace[K, V]
  ): KVEncoder[F, K] =
    KVEncoder[F, String].coMapF[K, String] { value =>
      encoder.encode(generic.to(value)).map(keySpace.name + KeySeparator + _)
    }

  implicit def kvStoreKeyDecoder[F[_]: MonadError[*[_], Throwable], K <: KVStoreKey, Repr <: HList, V](
    implicit generic: Generic.Aux[K, Repr],
    kvDecoder: KVDecoder[F, Repr],
    keySpace: KeySpace[K, V]
  ): KVDecoder[F, K] = {
    case KeyList(keySpaceName, keys @ _*) if keySpace.name == keySpaceName =>
      kvDecoder.decode(keys.mkString(KeySeparator)).map(generic.from)

    case value =>
      ApplicativeError[F, Throwable].raiseError(
        new IllegalArgumentException(s"""$value is not a key of ${keySpace.name}""")
      )
  }
}
