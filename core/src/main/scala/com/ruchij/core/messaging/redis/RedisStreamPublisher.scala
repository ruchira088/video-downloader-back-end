package com.ruchij.core.messaging.redis

import cats.effect.kernel.{Async, Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.messaging.{MessagingTopic, Publisher}
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.effects.{XAddArgs, XTrimArgs}
import fs2.Pipe

/**
  * Appends messages to a Redis stream, capping it at roughly `maxLength` entries (XADD ... MAXLEN ~), since Redis
  * keeps every entry otherwise. Approximate trimming lets Redis drop whole macro nodes, which is much cheaper.
  */
class RedisStreamPublisher[F[_]: Sync, A](redisCommands: RedisCommands[F, String, String], maxLength: Long)(
  implicit messagingTopic: MessagingTopic[A]
) extends Publisher[F, A] {

  private val xAddArgs: XAddArgs =
    XAddArgs(
      xTrimArgs =
        Some(XTrimArgs(XTrimArgs.Strategy.MAXLEN(maxLength), XTrimArgs.Precision.Approximate()))
    )

  override val publish: Pipe[F, A, Unit] = _.evalMap(publishOne)

  override def publishOne(input: A): F[Unit] =
    redisCommands
      .xAdd(messagingTopic.name, RedisData(input).toMap(messagingTopic.jsonCodec), xAddArgs)
      .void
}

object RedisStreamPublisher {

  /**
    * Kafka retains messages by time instead, but Redis keeps streams in memory, so they are capped by length. Groups
    * lagging by more than this many messages lose the oldest ones.
    */
  val DefaultMaxLength: Long = 100_000

  def create[F[_]: Async, A: MessagingTopic](
    redisConfiguration: RedisConfiguration,
    maxLength: Long = DefaultMaxLength
  ): Resource[F, RedisStreamPublisher[F, A]] =
    Redis[F].utf8(redisConfiguration.uri).map { redisCommands =>
      new RedisStreamPublisher[F, A](redisCommands, maxLength)
    }
}
