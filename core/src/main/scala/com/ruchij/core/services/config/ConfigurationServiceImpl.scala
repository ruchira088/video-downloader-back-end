package com.ruchij.core.services.config

import cats.Monad
import cats.data.OptionT
import cats.implicits._
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder, KVEncoder}
import com.ruchij.core.kv.keys.KVStoreKey
import com.ruchij.core.services.config.models.ConfigKey

class ConfigurationServiceImpl[F[_]: Monad, G[_] <: ConfigKey with KVStoreKey](
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, G[_], String]
) extends ConfigurationService[F, G] {

  override def get[A: KVDecoder[F, *], K[_] <: G[_]](key: K[A]): F[Option[A]] =
    OptionT(keySpacedKeyValueStore.get(key))
      .semiflatMap(KVDecoder[F, A].decode)
      .value

  override def put[A: KVCodec[F, *], K[_] <: G[_]](key: K[A], value: A): F[Option[A]] =
    for {
      stringValue <- KVEncoder[F, A].encode(value)
      existingValue <- get(key)
      _ <- keySpacedKeyValueStore.put(key, stringValue)
    } yield existingValue

  override def delete[A: KVDecoder[F, *], K[_] <: G[_]](key: K[A]): F[Option[A]] =
    for {
      existingValue <- get(key)
      _ <- keySpacedKeyValueStore.remove(key)
    } yield existingValue
}
