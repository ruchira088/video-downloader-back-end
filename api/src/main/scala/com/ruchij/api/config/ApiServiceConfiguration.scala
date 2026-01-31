package com.ruchij.api.config

import cats.ApplicativeError
import com.ruchij.core.config.{
  KafkaConfiguration,
  RedisConfiguration,
  SentryConfiguration,
  SpaSiteRendererConfiguration,
  StorageConfiguration
}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

final case class ApiServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  storageConfiguration: StorageConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  redisConfiguration: RedisConfiguration,
  authenticationConfiguration: AuthenticationConfiguration,
  kafkaConfiguration: KafkaConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  sentryConfiguration: SentryConfiguration
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
