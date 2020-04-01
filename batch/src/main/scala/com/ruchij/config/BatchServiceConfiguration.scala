package com.ruchij.config

import cats.effect.Sync
import cats.~>
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class BatchServiceConfiguration(
  downloadConfiguration: DownloadConfiguration,
  workerConfiguration: WorkerConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object BatchServiceConfiguration {
  def parse[F[_]: Sync](
    configObjectSource: ConfigObjectSource
  )(implicit functionK: Either[Throwable, *] ~> F): F[BatchServiceConfiguration] =
    Sync[F].defer {
      functionK {
        configObjectSource.load[BatchServiceConfiguration].left.map(ConfigReaderException.apply)
      }
    }
}
