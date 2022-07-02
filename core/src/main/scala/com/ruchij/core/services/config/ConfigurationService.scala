package com.ruchij.core.services.config

import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder}
import com.ruchij.core.services.config.models.ConfigKey

trait ConfigurationService[F[_], C[_] <: ConfigKey] {
  def get[A : KVDecoder[F, *], K[_] <: C[_]](key: K[A]): F[Option[A]]

  def put[A : KVCodec[F, *], K[_] <: C[_]](key: K[A], value: A): F[Option[A]]

  def delete[A : KVDecoder[F, *], K[_] <: C[_]](key: K[A]): F[Option[A]]
}
