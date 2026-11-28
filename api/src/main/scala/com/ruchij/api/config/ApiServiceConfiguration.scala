package com.ruchij.api.config

import cats.ApplicativeThrow
import com.ruchij.core.config.*
import com.ruchij.core.types.FunctionKTypes.*
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigObjectSource
import pureconfig.ConfigReader
import pureconfig.error.ConfigReaderException
import pureconfig.generic.semiauto.deriveReader

final case class ApiServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  storageConfiguration: StorageConfiguration,
  databaseConfiguration: DatabaseConfiguration,
  redisConfiguration: RedisConfiguration,
  authenticationConfiguration: AuthenticationConfiguration,
  pubsubConfiguration: PubsubConfiguration,
  spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  sentryConfiguration: SentryConfiguration,
  httpProxyConfiguration: Option[HttpProxyConfiguration],
  fallbackSyncConfiguration: FallbackSyncConfiguration = FallbackSyncConfiguration.Disabled
)

object ApiServiceConfiguration {
  implicit val configReader: ConfigReader[ApiServiceConfiguration] = deriveReader[ApiServiceConfiguration]

  def parse[F[_]: ApplicativeThrow](configObjectSource: ConfigObjectSource): F[ApiServiceConfiguration] =
    configObjectSource
      .load[ApiServiceConfiguration]
      .left
      .map(failure => ConfigReaderException.apply[ApiServiceConfiguration](failure))
      .toType[F, Throwable]
}
