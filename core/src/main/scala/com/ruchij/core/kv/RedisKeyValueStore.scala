package com.ruchij.core.kv

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import cats.{Applicative, MonadThrow}
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import dev.profunktor.redis4cats.effects.SetArgs
import dev.profunktor.redis4cats.effects.SetArg.Ttl.Px

import scala.concurrent.duration.FiniteDuration

class RedisKeyValueStore[F[_]: MonadThrow](redisCommands: RedisCommands[F, String, String]) extends KeyValueStore[F] {
  override type InsertionResult = Unit
  override type DeletionResult = Unit

  override def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      fetchedValue <- redisCommands.get(encodedKey)
      value <- fetchedValue.fold[F[Option[V]]](Applicative[F].pure(None)) { stringValue =>
        MonadThrow[F].handleErrorWith[Option[V]](KVDecoder[F, V].decode(stringValue).map(Some.apply)) { _ => remove(key).as(None) }
      }
    } yield value

  override def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V, maybeTtl: Option[FiniteDuration]): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      encodedValue <- KVEncoder[F, V].encode(value)

      _ <- redisCommands.set(encodedKey, encodedValue, SetArgs(None, maybeTtl.map(ttl => Px(ttl))))
    } yield (): Unit

  override def remove[K: KVEncoder[F, *]](key: K): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      _ <- redisCommands.del(encodedKey)
    } yield (): Unit
}

object RedisKeyValueStore {
  import dev.profunktor.redis4cats.effect.Log.Stdout.instance

  def create[F[_]: Async](redisConfiguration: RedisConfiguration): Resource[F, RedisKeyValueStore[F]] =
    Redis[F].utf8(redisConfiguration.uri).map { redisCommands => new RedisKeyValueStore[F](redisCommands) }
}
