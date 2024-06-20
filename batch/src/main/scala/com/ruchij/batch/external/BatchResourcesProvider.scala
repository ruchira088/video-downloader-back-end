package com.ruchij.batch.external

import cats.effect.Resource
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.migration.config.DatabaseConfiguration

trait BatchResourcesProvider[F[_]] extends CoreResourcesProvider[F] {
  protected val externalCoreServiceProvider: CoreResourcesProvider[F]

  override lazy val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    externalCoreServiceProvider.kafkaConfiguration

  override lazy val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    externalCoreServiceProvider.databaseConfiguration

  override lazy val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    externalCoreServiceProvider.spaSiteRendererConfiguration
}
