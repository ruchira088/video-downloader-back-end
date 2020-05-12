package com.ruchij.config

import cats.ApplicativeError
import com.ruchij.config.PureConfigReaders.localTimePureConfigReader
import com.ruchij.types.FunctionKTypes.eitherToF
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class BatchServiceConfiguration(
  downloadConfiguration: DownloadConfiguration,
  workerConfiguration: WorkerConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object BatchServiceConfiguration {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[BatchServiceConfiguration] =
    eitherToF.apply {
      configObjectSource.load[BatchServiceConfiguration].left.map(ConfigReaderException.apply)
    }
}
