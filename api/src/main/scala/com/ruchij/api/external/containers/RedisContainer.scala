package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.api.external.containers.RedisContainer.Port
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.containers.ContainerExternalCoreServiceProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy

import scala.jdk.CollectionConverters.SeqHasAsJava

class RedisContainer extends GenericContainer[RedisContainer]("bitnami/redis:7.0") {
  setExposedPorts(List[Integer](Port).asJava)
  setWaitStrategy(new LogMessageWaitStrategy().withRegEx(".*Ready to accept connections.*\\n"))
}

object RedisContainer {
  private val Port = 6379
  private val Password = "my-password"

  def create[F[_]: Sync]: Resource[F, RedisConfiguration] =
    Resource.eval(Sync[F].delay(new RedisContainer().withEnv("REDIS_PASSWORD", Password)))
      .flatMap(redisContainer => ContainerExternalCoreServiceProvider.start(redisContainer))
      .evalMap { redisContainer =>
        for {
          host <- Sync[F].blocking(redisContainer.getHost)
          port <- Sync[F].blocking(redisContainer.getMappedPort(Port))
        }
        yield RedisConfiguration(host, port, Some(Password))
      }
}