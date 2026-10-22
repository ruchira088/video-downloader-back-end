package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.redis.testcontainers.{RedisContainer => RedisTestContainer}
import com.ruchij.core.config.RedisConfiguration
import io.lettuce.core.{RedisClient, RedisURI}

import java.time.Duration
import scala.util.Using

class RedisContainer extends RedisTestContainer("redis:8")

object RedisContainer {
  private val Password = "my-password"

  // A started container's mapped port can refuse connections for a moment on a loaded machine, so the container is
  // only handed out once fresh connections to it succeed
  private val ReadinessAttempts = 5

  // Once a container's mapped port stops answering, retrying it doesn't help, so a fresh container is started instead
  private val ContainerAttempts = 3

  def create[F[_]: Sync]: Resource[F, RedisConfiguration] = readyContainer(ContainerAttempts)

  private def readyContainer[F[_]: Sync](attempts: Int): Resource[F, RedisConfiguration] =
    container[F].attempt.flatMap {
      case Right(redisConfiguration) => Resource.pure(redisConfiguration)
      case Left(_) if attempts > 1 => readyContainer(attempts - 1)
      case Left(throwable) => Resource.raiseError[F, RedisConfiguration, Throwable](throwable)
    }

  private def container[F[_]: Sync]: Resource[F, RedisConfiguration] =
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
      .evalTap(redisConfiguration => awaitReady(redisConfiguration, ReadinessAttempts))

  private def awaitReady[F[_]: Sync](redisConfiguration: RedisConfiguration, attempts: Int): F[Unit] =
    Sync[F]
      .blocking {
        val redisUri =
          RedisURI.builder()
            .withHost(redisConfiguration.hostname)
            .withPort(redisConfiguration.port)
            .withPassword(Password.toCharArray)
            .withTimeout(Duration.ofSeconds(5))
            .build()

        val client = RedisClient.create(redisUri)

        try Using.resource(client.connect())(_.sync().ping())
        finally client.shutdown()
      }
      .void
      .handleErrorWith { throwable =>
        if (attempts > 1) Sync[F].blocking(Thread.sleep(1000)) >> awaitReady(redisConfiguration, attempts - 1)
        else Sync[F].raiseError(throwable)
      }
}
