package com.ruchij.config

import cats.effect.Sync
import cats.~>
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class WebServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  downloadConfiguration: DownloadConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object WebServiceConfiguration {
  def parse[F[_]: Sync](
    configObjectSource: ConfigObjectSource
  )(implicit functionK: Either[Throwable, *] ~> F): F[WebServiceConfiguration] =
    Sync[F].defer {
      functionK {
        configObjectSource.load[WebServiceConfiguration].left.map(ConfigReaderException.apply)
      }
    }
}
