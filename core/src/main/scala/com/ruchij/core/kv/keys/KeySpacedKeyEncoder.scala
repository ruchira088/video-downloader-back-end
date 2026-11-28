package com.ruchij.core.kv.keys

import cats.Functor
import cats.implicits._
import com.ruchij.core.kv.codecs.KVEncoder
import com.ruchij.core.kv.keys.KVStoreKey.KeySeparator

trait KeySpacedKeyEncoder[F[_], K <: KVStoreKey] extends KVEncoder[F, K]

object KeySpacedKeyEncoder {
  def apply[F[_], K <: KVStoreKey](implicit encoder: KeySpacedKeyEncoder[F, K]): KeySpacedKeyEncoder[F, K] =
    encoder

  // A key is stored with its key space's name in front of the key's own encoding
  def of[F[_]: Functor, K <: KVStoreKey, V](
    keySpace: KeySpace[K, V],
    keyEncoder: KVEncoder[F, K]
  ): KeySpacedKeyEncoder[F, K] =
    (value: K) => keyEncoder.encode(value).map(keySpace.name + KeySeparator + _)
}
