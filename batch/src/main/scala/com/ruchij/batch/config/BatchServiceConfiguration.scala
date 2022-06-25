package com.ruchij.batch.config

import cats.ApplicativeError
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class BatchServiceConfiguration(
  storageConfiguration: BatchStorageConfiguration,
  workerConfiguration: WorkerConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  kafkaConfiguration: KafkaConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  applicationInformation: ApplicationInformation
)

object BatchServiceConfiguration {

  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[BatchServiceConfiguration] =
    configObjectSource.load[BatchServiceConfiguration].left.map(ConfigReaderException.apply).toType[F, Throwable]

}
