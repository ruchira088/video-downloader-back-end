package com.ruchij.api.external.embedded

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.api.external.containers.ContainerApiResourcesProvider
import com.ruchij.core.config.{RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import redis.embedded.RedisServer

class EmbeddedApiResourcesProvider[F[_]: Sync] extends ApiResourcesProvider[F] {
  private val containerExternalApiServiceProvider = new ContainerApiResourcesProvider[F]

  protected val externalCoreServiceProvider: EmbeddedCoreResourcesProvider[F] =
    new EmbeddedCoreResourcesProvider[F]

  override lazy val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    containerExternalApiServiceProvider.spaSiteRendererConfiguration

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource
      .eval(EmbeddedCoreResourcesProvider.availablePort[F](6300))
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
}
