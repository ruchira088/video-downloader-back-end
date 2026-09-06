package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.redis.testcontainers.{RedisContainer => RedisTestContainer}
import com.ruchij.core.config.RedisConfiguration

class RedisContainer extends RedisTestContainer("redis:8")

object RedisContainer {
  private val Password = "my-password"

  def create[F[_]: Sync]: Resource[F, RedisConfiguration] =
    Resource.eval {
      Sync[F].delay { new RedisContainer().withCommand(s"redis-server --requirepass $Password") }
    }
      .flatMap(redisContainer => ContainerCoreResourcesProvider.start(redisContainer))
      .evalMap { redisContainer =>
        for {
          host <- Sync[F].blocking(redisContainer.getHost)
          port <- Sync[F].blocking(redisContainer.getRedisPort)
        }
        yield RedisConfiguration(host, port, Some(Password))
      }
}