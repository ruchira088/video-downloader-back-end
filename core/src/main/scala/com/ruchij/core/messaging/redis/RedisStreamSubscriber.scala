package com.ruchij.core.messaging.redis

import cats.effect.kernel.{Async, Resource, Sync}
import cats.implicits._
import cats.{Applicative, Foldable, Functor, Id}
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.effects.XReadOffsets
import dev.profunktor.redis4cats.streams.RedisStream
import fs2.Stream

class RedisStreamSubscriber[F[_]: Sync, A](redisStream: RedisStream[F, String, String])(
  implicit redisStreamTopic: RedisStreamTopic[A]
) extends Subscriber[F, Id, A] {
  private val logger = Logger[RedisStreamSubscriber[F, A]]

  override def subscribe(groupId: String): Stream[F, A] =
    redisStream
      .read(XReadOffsets.latest(redisStreamTopic.streamKey))
      .flatMap { streamMessage =>
        RedisData.from(streamMessage.body)(redisStreamTopic.codec) match {
          case Right(value) => Stream(value)
          case Left(exception) =>
            Stream
              .eval {
                logger.error("Unable to parse message", exception)
              }
              .productR(Stream.empty)
        }
      }

  override def commit[H[_]: Foldable: Functor](values: H[A]): F[Unit] =
    Applicative[F].unit
}

object RedisStreamSubscriber {
  def create[F[_]: Async, A: RedisStreamTopic](
    redisConfiguration: RedisConfiguration
  ): Resource[F, RedisStreamSubscriber[F, A]] =
    Redis[F].utf8(redisConfiguration.uri).map { redisCommands =>
      new RedisStreamSubscriber[F, A](RedisStream[F, String, String](redisCommands))
    }
}
