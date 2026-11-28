package com.ruchij.core.services.config

import cats.Monad
import cats.data.OptionT
import cats.implicits.*
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder, KVEncoder}
import com.ruchij.core.kv.keys.KVStoreKey
import com.ruchij.core.services.config.models.ConfigKey

// K is the type of every key in the key space, such as SharedConfigKey[?] for the SharedConfigKey keys
class ConfigurationServiceImpl[F[_]: Monad, C[_] <: ConfigKey & K, K <: KVStoreKey](
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, K, String]
) extends ConfigurationService[F, C] {

  override def get[A: KVDecoder[F, *]](key: C[A]): F[Option[A]] =
    OptionT(keySpacedKeyValueStore.get(key))
      .semiflatMap(KVDecoder[F, A].decode)
      .value

  override def put[A: KVCodec[F, *]](key: C[A], value: A): F[Option[A]] =
    for {
      stringValue <- KVEncoder[F, A].encode(value)
      existingValue <- get(key)
      _ <- keySpacedKeyValueStore.put(key, stringValue)
    } yield existingValue

  override def delete[A: KVDecoder[F, *]](key: C[A]): F[Option[A]] =
    for {
      existingValue <- get(key)
      _ <- keySpacedKeyValueStore.remove(key)
    } yield existingValue
}
