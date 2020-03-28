package com.ruchij.config

import cats.effect.Sync
import cats.~>
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class ServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  downloadConfiguration: DownloadConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object ServiceConfiguration {
  def parse[F[_]: Sync](
    configObjectSource: ConfigObjectSource
  )(implicit functionK: Either[Throwable, *] ~> F): F[ServiceConfiguration] =
    Sync[F].defer {
      functionK {
        configObjectSource.load[ServiceConfiguration].left.map(ConfigReaderException.apply)
      }
    }
}
