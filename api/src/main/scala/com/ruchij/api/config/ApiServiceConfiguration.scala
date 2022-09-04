package com.ruchij.api.config

import cats.ApplicativeError
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

final case class ApiServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  storageConfiguration: ApiStorageConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  redisConfiguration: RedisConfiguration,
  authenticationConfiguration: AuthenticationConfiguration,
  kafkaConfiguration: KafkaConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  applicationInformation: ApplicationInformation
)

object ApiServiceConfiguration {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[ApiServiceConfiguration] =
    configObjectSource
      .load[ApiServiceConfiguration]
      .left
      .map(failure => ConfigReaderException.apply[ApiServiceConfiguration](failure))
      .toType[F, Throwable]
}
