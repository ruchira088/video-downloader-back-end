package com.ruchij.core.messaging.redis

import cats.effect.kernel.{Async, Resource, Sync}
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.messaging.Publisher
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.streams.data.XAddMessage
import fs2.{Pipe, Stream}

class RedisStreamPublisher[F[_]: Sync, A](redisStream: RedisStream[F, String, String])(
  implicit redisStreamTopic: RedisStreamTopic[A]
) extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    input =>
      redisStream
        .append {
          input.map { value =>
            XAddMessage(redisStreamTopic.streamKey, RedisData(value).toMap(redisStreamTopic.codec))
          }
        }
        .as((): Unit)

  override def publishOne(input: A): F[Unit] =
    publish(Stream.emit[F, A](input)).compile.drain
}

object RedisStreamPublisher {

  def create[F[_]: Async, A: RedisStreamTopic](
    redisConfiguration: RedisConfiguration
  ): Resource[F, RedisStreamPublisher[F, A]] =
    Redis[F].utf8(redisConfiguration.uri).map { redisCommands =>
      new RedisStreamPublisher[F, A](RedisStream[F, String, String](redisCommands))
    }
}
