package com.ruchij.api.external.containers

import cats.effect.Resource
import cats.effect.kernel.Sync
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider

class ContainerApiResourcesProvider[F[_]: Sync] extends ApiResourcesProvider[F] {

  override protected val externalCoreServiceProvider: CoreResourcesProvider[F] =
    new ContainerCoreResourcesProvider[F]

  override val redisConfiguration: Resource[F, RedisConfiguration] = RedisContainer.create[F]
}
