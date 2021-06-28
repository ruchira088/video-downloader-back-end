package com.ruchij.api.config

import cats.ApplicativeError
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class ApiServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  storageConfiguration: ApiStorageConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  redisConfiguration: RedisConfiguration,
  authenticationConfiguration: AuthenticationConfiguration,
  kafkaConfiguration: KafkaConfiguration,
  applicationInformation: ApplicationInformation
)

object ApiServiceConfiguration {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[ApiServiceConfiguration] =
    FunctionKTypes.eitherToF.apply {
      configObjectSource
        .load[ApiServiceConfiguration]
        .left
        .map(failure => ConfigReaderException.apply[ApiServiceConfiguration](failure))
    }
}
