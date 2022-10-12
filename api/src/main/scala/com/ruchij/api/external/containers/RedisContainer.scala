package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.containers.ContainerExternalCoreServiceProvider
import org.testcontainers.containers.GenericContainer

class RedisContainer extends GenericContainer[RedisContainer]("bitnami/redis:7.0")

object RedisContainer {
  private val Port = 6379
  private val Password = "my-password"

  def create[F[_]: Sync]: Resource[F, RedisConfiguration] =
    Resource.eval(Sync[F].delay(new RedisContainer().withExposedPorts(Port).withEnv("REDIS_PASSWORD", Password)))
      .flatMap(redisContainer => ContainerExternalCoreServiceProvider.start(redisContainer))
      .evalMap { redisContainer =>
        for {
          host <- Sync[F].blocking(redisContainer.getHost)
          port <- Sync[F].blocking(redisContainer.getMappedPort(Port))
        }
        yield RedisConfiguration(host, port, Some(Password))
      }
}