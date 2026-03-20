package com.ruchij.core.kv

import cats.implicits._
import cats.{Applicative, MonadThrow, ~>}
import com.ruchij.core.daos.keyvalue.KeyValueDao
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.types.Clock

import scala.concurrent.duration.FiniteDuration

class DoobieKeyValueStore[F[_]: MonadThrow: Clock, G[_]](keyValueDao: KeyValueDao[G])(
    implicit transaction: G ~> F
) extends KeyValueStore[F] {
  override type InsertionResult = Unit
  override type DeletionResult = Unit

  override def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      now <- Clock[F].timestamp
      fetchedValue <- transaction(keyValueDao.find(encodedKey, now))
      value <- fetchedValue.fold[F[Option[V]]](Applicative[F].pure(None)) { stringValue =>
        MonadThrow[F].handleErrorWith[Option[V]](KVDecoder[F, V].decode(stringValue).map(Some.apply)) { _ =>
          remove(key).as(None)
        }
      }
    } yield value

  override def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V, maybeTtl: Option[FiniteDuration]): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      encodedValue <- KVEncoder[F, V].encode(value)
      now <- Clock[F].timestamp
      expiresAt = maybeTtl.map(ttl => now.plusMillis(ttl.toMillis))
      _ <- transaction(keyValueDao.insert(encodedKey, encodedValue, expiresAt))
    } yield ()

  override def remove[K: KVEncoder[F, *]](key: K): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      _ <- transaction(keyValueDao.delete(encodedKey))
    } yield ()
}
