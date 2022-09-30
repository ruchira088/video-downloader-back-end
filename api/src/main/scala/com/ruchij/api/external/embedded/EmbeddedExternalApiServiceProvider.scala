package com.ruchij.api.external.embedded

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.api.external.ExternalApiServiceProvider
import com.ruchij.api.external.containers.ContainerExternalApiServiceProvider
import com.ruchij.core.config.{RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.embedded.EmbeddedExternalCoreServiceProvider
import redis.embedded.RedisServer

class EmbeddedExternalApiServiceProvider[F[_]: Sync] extends ExternalApiServiceProvider[F] {
  private val containerExternalApiServiceProvider = new ContainerExternalApiServiceProvider[F]

  protected val externalCoreServiceProvider: EmbeddedExternalCoreServiceProvider[F] =
    new EmbeddedExternalCoreServiceProvider[F]

  override lazy val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    containerExternalApiServiceProvider.spaSiteRendererConfiguration

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource
      .eval(EmbeddedExternalCoreServiceProvider.availablePort[F](6300))
      .flatMap { port =>
        Resource
          .make {
            Sync[F]
              .blocking(RedisServer.builder().port(port).build())
              .flatTap(redisServer => Sync[F].blocking(redisServer.start()))
          } { redisServer =>
            Sync[F].blocking(redisServer.stop())
          }
          .as(RedisConfiguration("localhost", port, None))
      }

  override val fallbackApiConfiguration: Resource[F, FallbackApiConfiguration] =
    containerExternalApiServiceProvider.fallbackApiConfiguration
}
