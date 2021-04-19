package com.ruchij.core.kv.keys

import cats.Monad
import cats.implicits._
import com.ruchij.core.kv.codecs.KVEncoder
import com.ruchij.core.kv.keys.KVStoreKey.KeySeparator
import shapeless.{Generic, HList}

trait KeySpacedKeyEncoder[F[_], K <: KVStoreKey] extends KVEncoder[F, K]

object KeySpacedKeyEncoder {
  implicit def keySpacedKeyEncoder[F[_]: Monad, K <: KVStoreKey, Repr <: HList, V](
    implicit generic: Generic.Aux[K, Repr],
    encoder: KVEncoder[F, Repr],
    keySpace: KeySpace[K, V]
  ): KeySpacedKeyEncoder[F, K] =
    (value: K) =>
      encoder.encode(generic.to(value)).map(keySpace.name + KeySeparator + _)
}
