package com.ruchij.api.external.local

import cats.Applicative
import cats.effect.Resource
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.local.LocalCoreResourcesProvider

class LocalApiResourcesProvider[F[_]: Applicative]
    extends ApiResourcesProvider[F] {

  protected val externalCoreServiceProvider: LocalCoreResourcesProvider[F] =
    new LocalCoreResourcesProvider[F]

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource.pure(RedisConfiguration("localhost", 6379, None))
}
