package com.ruchij.batch.config

import cats.ApplicativeError
import com.ruchij.core.config.{DownloadConfiguration, RedisConfiguration}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class BatchServiceConfiguration(
  downloadConfiguration: DownloadConfiguration,
  workerConfiguration: WorkerConfiguration,
  redisConfiguration: RedisConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object BatchServiceConfiguration {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[BatchServiceConfiguration] =
    FunctionKTypes.eitherToF.apply {
      configObjectSource.load[BatchServiceConfiguration].left.map(ConfigReaderException.apply)
    }
}
