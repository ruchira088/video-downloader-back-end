package com.ruchij.config

import cats.ApplicativeError
import com.ruchij.types.FunctionKTypes.eitherToF
import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class WebServiceConfiguration(
  httpConfiguration: HttpConfiguration,
  downloadConfiguration: DownloadConfiguration,
  databaseConfiguration: DatabaseConfiguration
)

object WebServiceConfiguration {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](
    configObjectSource: ConfigObjectSource
  ): F[WebServiceConfiguration] =
    eitherToF.apply {
      configObjectSource
        .load[WebServiceConfiguration]
        .left
        .map(failure => ConfigReaderException.apply[WebServiceConfiguration](failure))
    }
}
