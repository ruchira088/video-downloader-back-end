package com.ruchij.core.kv

import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}

import scala.concurrent.duration.FiniteDuration

trait KeyValueStore[F[_]] {
  type InsertionResult
  type DeletionResult

  def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]]

  def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V, maybeTtl: Option[FiniteDuration]): F[InsertionResult]

  def remove[K: KVEncoder[F, *]](key: K): F[DeletionResult]
}