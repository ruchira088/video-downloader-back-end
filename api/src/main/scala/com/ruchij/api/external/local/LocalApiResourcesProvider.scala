package com.ruchij.api.external.local

import cats.Applicative
import cats.effect.Resource
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.local.LocalCoreResourcesProvider
import org.http4s.implicits.http4sLiteralsSyntax

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class LocalApiResourcesProvider[F[_]: Applicative]
    extends ApiResourcesProvider[F] {

  protected val externalCoreServiceProvider: LocalCoreResourcesProvider[F] =
    new LocalCoreResourcesProvider[F]

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource.pure(RedisConfiguration("localhost", 6379, None))

  override val fallbackApiConfiguration: Resource[F, FallbackApiConfiguration] =
    Resource.pure(FallbackApiConfiguration(uri"http://localhost:8080", "my-bearer-token", 5 minutes))

}
