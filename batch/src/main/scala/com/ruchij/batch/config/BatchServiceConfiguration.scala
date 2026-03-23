package com.ruchij.batch.config

import cats.ApplicativeError
import com.ruchij.core.config.{PubsubConfiguration, RedisConfiguration, SentryConfiguration, SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

final case class BatchServiceConfiguration(
  storageConfiguration: StorageConfiguration,
  workerConfiguration: WorkerConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  pubsubConfiguration: PubsubConfiguration,
  redisConfiguration: RedisConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  sentryConfiguration: SentryConfiguration
)

object BatchServiceConfiguration {

  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[BatchServiceConfiguration] =
    configObjectSource.load[BatchServiceConfiguration].left.map(ConfigReaderException.apply).toType[F, Throwable]

}
