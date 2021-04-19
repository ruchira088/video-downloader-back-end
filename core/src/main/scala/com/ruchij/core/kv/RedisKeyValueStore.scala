package com.ruchij.core.kv

import cats.implicits._
import cats.{Applicative, Monad}
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import dev.profunktor.redis4cats.RedisCommands

import scala.concurrent.duration.FiniteDuration

class RedisKeyValueStore[F[_]: Monad](redisCommands: RedisCommands[F, String, String]) extends KeyValueStore[F] {
  override type InsertionResult = Unit
  override type DeletionResult = Unit

  override def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      fetchedValue <- redisCommands.get(encodedKey)
      value <- fetchedValue.fold[F[Option[V]]](Applicative[F].pure(None)) { stringValue =>
        KVDecoder[F, V].decode(stringValue).map(Some.apply)
      }
    } yield value

  override def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V, ttl: FiniteDuration): F[InsertionResult] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      encodedValue <- KVEncoder[F, V].encode(value)

      result <- redisCommands.set(encodedKey, encodedValue)
      _ <- redisCommands.expire(encodedKey, ttl)
    } yield result

  override def remove[K: KVEncoder[F, *]](key: K): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      _ <- redisCommands.del(encodedKey)
    } yield (): Unit
}
