package com.ruchij.kv

import cats.Functor
import cats.implicits._
import com.ruchij.kv.codecs.KVCodec
import com.ruchij.kv.keys.{KVStoreKey, KeySpace}

class KeySpacedKeyValueStore[F[_]: Functor, K <: KVStoreKey[K] : KVCodec[F, *], V: KVCodec[F, *]](
  keySpace: KeySpace[K, V],
  keyValueStore: KeyValueStore[F]
) {
  def get(key: K): F[Option[V]] = keyValueStore.get[K, V](key)

  def put(key: K, value: V): F[Unit] = keyValueStore.put[K, V](key, value).map(_ => (): Unit)

  def remove(key: K): F[Unit] = keyValueStore.remove(key).map(_ => (): Unit)

  val allKeys: F[List[K]] = keyValueStore.keys[K](keySpace.name)
}
