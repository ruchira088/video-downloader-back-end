package com.ruchij.core.services.config

import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder}
import com.ruchij.core.services.config.models.ConfigKey

trait ConfigurationService[F[_], C[_] <: ConfigKey] {
  def get[A: KVDecoder[F, *]](key: C[A]): F[Option[A]]

  def put[A: KVCodec[F, *]](key: C[A], value: A): F[Option[A]]

  def delete[A: KVDecoder[F, *]](key: C[A]): F[Option[A]]
}
