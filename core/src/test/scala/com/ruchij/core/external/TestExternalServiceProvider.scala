package com.ruchij.core.external

import cats.effect.Resource
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.TestExternalServiceProvider.CiEnvName
import com.ruchij.core.external.containers.ContainerExternalServiceProvider
import com.ruchij.core.external.local.LocalExternalServiceProvider
import com.ruchij.migration.config.DatabaseConfiguration

class TestExternalServiceProvider[F[_]](
  localExternalServiceProvider: LocalExternalServiceProvider[F],
  containerExternalServiceProvider: ContainerExternalServiceProvider[F],
  environmentVariables: Map[String, String]
) extends ExternalServiceProvider[F] {

  private val externalServiceProvider =
    if (environmentVariables.get(CiEnvName).flatMap(_.toBooleanOption).getOrElse(false)) localExternalServiceProvider
    else containerExternalServiceProvider

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    externalServiceProvider.redisConfiguration

  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    externalServiceProvider.kafkaConfiguration

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    externalServiceProvider.databaseConfiguration

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    externalServiceProvider.spaSiteRendererConfiguration

}

object TestExternalServiceProvider {
  private val CiEnvName: String = "CI"
}
