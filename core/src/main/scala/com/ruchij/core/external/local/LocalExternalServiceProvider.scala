package com.ruchij.core.external.local

import cats.Applicative
import cats.effect.Resource
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.ExternalServiceProvider
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax

class LocalExternalServiceProvider[F[_]: Applicative] extends ExternalServiceProvider[F] {
  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource.pure(RedisConfiguration("localhost", 6379, None))

  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    Resource.pure(KafkaConfiguration("localhost:9092", uri"http://localhost:8081"))

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    Resource.pure(DatabaseConfiguration("jdbc:postgresql://localhost:5432/video-downloader", "admin", "password"))

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    Resource.pure(SpaSiteRendererConfiguration(uri"http://localhost:8000"))
}
