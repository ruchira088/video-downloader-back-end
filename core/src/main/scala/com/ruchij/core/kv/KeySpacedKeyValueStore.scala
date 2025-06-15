package com.ruchij.core.kv

import cats.Functor
import cats.implicits._
import com.ruchij.core.kv.codecs.KVCodec
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}

class KeySpacedKeyValueStore[F[_]: Functor, K <: KVStoreKey : KeySpacedKeyEncoder[F, *], V: KVCodec[F, *]](
  keySpace: KeySpace[K, V],
  keyValueStore: KeyValueStore[F]
) {
  def get(key: K): F[Option[V]] = keyValueStore.get[K, V](key)

  def put(key: K, value: V): F[Unit] = keyValueStore.put[K, V](key, value, key.maybeTtl.orElse(keySpace.maybeTtl)).as((): Unit)

  def remove(key: K): F[Unit] = keyValueStore.remove(key).as((): Unit)
}
