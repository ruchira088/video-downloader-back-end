package com.ruchij.api.external.containers

import cats.effect.Resource
import cats.effect.kernel.Sync
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.api.external.ExternalApiServiceProvider
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.ExternalCoreServiceProvider
import com.ruchij.core.external.containers.ContainerExternalCoreServiceProvider

class ContainerExternalApiServiceProvider[F[_]: Sync] extends ExternalApiServiceProvider[F] {

  override protected val externalCoreServiceProvider: ExternalCoreServiceProvider[F] =
    new ContainerExternalCoreServiceProvider[F]

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    ContainerExternalCoreServiceProvider
      .start(new RedisContainer())
      .evalMap(_.redisConfiguration[F])

  override val fallbackApiConfiguration: Resource[F, FallbackApiConfiguration] =
    ContainerExternalCoreServiceProvider
      .start(new FallbackApiContainer())
      .evalMap(_.fallbackApiConfiguration)
}
