package com.ruchij.core.kv

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

class InMemoryKeyValueStore[F[_]: Sync] extends KeyValueStore[F] {

  override type InsertionResult = Boolean
  override type DeletionResult = Boolean

  val data = new ConcurrentHashMap[String, String]()

  def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]] =
    for {
      keyString <- KVEncoder[F, K].encode(key)

      maybeValueString <- Sync[F].delay(Option(data.get(keyString)))
      maybeValue <- maybeValueString.map(valueString => KVDecoder[F, V].decode(valueString)).sequence
    }
    yield maybeValue

  def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V, maybeTtl: Option[FiniteDuration]): F[Boolean] =
    for {
      keyString <- KVEncoder[F, K].encode(key)
      valueString <- KVEncoder[F, V].encode(value)

      maybePastValueString <- Sync[F].delay(Option(data.put(keyString, valueString)))
    }
    yield maybePastValueString.nonEmpty

  def remove[K: KVEncoder[F, *]](key: K): F[Boolean] =
    for {
      keyString <- KVEncoder[F, K].encode(key)

      maybeValueString <- Sync[F].delay(Option(data.remove(keyString)))
    }
    yield maybeValueString.nonEmpty

}
