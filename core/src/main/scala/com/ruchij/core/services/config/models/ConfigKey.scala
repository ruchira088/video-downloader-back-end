package com.ruchij.core.services.config.models

import cats.Applicative
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}

trait ConfigKey {
  val key: String
}

object ConfigKey {
  given configKeySpacedEncoder[F[_]: Applicative, C <: ConfigKey & KVStoreKey, D](using
    keyspace: KeySpace[C, D]
  ): KeySpacedKeyEncoder[F, C] =
    (value: C) => Applicative[F].pure(keyspace.name + KVStoreKey.KeySeparator + value.key)
}
