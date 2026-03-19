package com.ruchij.core.messaging.redis

import cats.effect.kernel.Sync
import com.ruchij.core.messaging.Publisher
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
