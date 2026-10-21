package com.ruchij.core.kv

import cats.effect.kernel.{Async, Resource}
import cats.effect.syntax.spawn._
import cats.implicits._
import cats.{Applicative, MonadThrow, ~>}
import com.ruchij.core.daos.keyvalue.KeyValueDao
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream

import scala.concurrent.duration._
import scala.language.postfixOps

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

  /** Deletes the expired entries. Reads already ignore them, so this only reclaims the space they take up. */
  val purgeExpiredKeys: F[Int] =
    Clock[F].timestamp.flatMap(timestamp => transaction(keyValueDao.deleteExpiredKeys(timestamp)))
}

object DoobieKeyValueStore {
  private val logger = Logger[DoobieKeyValueStore.type]

  val DefaultPurgeInterval: FiniteDuration = 10 minutes

  /** Creates the store along with a background fiber that purges expired entries every `purgeInterval`. */
  def create[F[_]: Async: Clock, G[_]](
    keyValueDao: KeyValueDao[G],
    purgeInterval: FiniteDuration = DefaultPurgeInterval
  )(implicit transaction: G ~> F): Resource[F, DoobieKeyValueStore[F, G]] = {
    val keyValueStore = new DoobieKeyValueStore[F, G](keyValueDao)

    val purge: F[Unit] =
      keyValueStore.purgeExpiredKeys
        .flatMap { count =>
          if (count > 0) logger.debug[F](s"Purged $count expired key-value entries") else Applicative[F].unit
        }
        .handleErrorWith { throwable =>
          // Try again at the next interval
          logger.error[F]("Unable to purge expired key-value entries", throwable)
        }

    Stream
      .awakeDelay[F](purgeInterval)
      .evalMap(_ => purge)
      .compile
      .drain
      .background
      .as(keyValueStore)
  }
}
