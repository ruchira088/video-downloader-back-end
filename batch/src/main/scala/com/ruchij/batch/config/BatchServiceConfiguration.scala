package com.ruchij.batch.config

import cats.ApplicativeThrow
import com.ruchij.core.config.{
  HttpProxyConfiguration,
  PubsubConfiguration,
  RedisConfiguration,
  SentryConfiguration,
  SpaSiteRendererConfiguration,
  StorageConfiguration
}
import com.ruchij.core.types.FunctionKTypes.{given, *}
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.ConfigReader
import pureconfig.error.ConfigReaderException

final case class BatchServiceConfiguration(
  storageConfiguration: StorageConfiguration,
  workerConfiguration: WorkerConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  pubsubConfiguration: PubsubConfiguration,
  redisConfiguration: RedisConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  sentryConfiguration: SentryConfiguration,
  httpProxyConfiguration: Option[HttpProxyConfiguration],
  retryConfiguration: RetryConfiguration = RetryConfiguration.Default,
  healthConfiguration: HealthConfiguration = HealthConfiguration()
) derives ConfigReader

object BatchServiceConfiguration {
  def parse[F[_]: ApplicativeThrow](configObjectSource: ConfigObjectSource): F[BatchServiceConfiguration] =
    configObjectSource
      .load[BatchServiceConfiguration]
      .left
      .map(ConfigReaderException.apply[BatchServiceConfiguration])
      .toType[F, Throwable]

}
