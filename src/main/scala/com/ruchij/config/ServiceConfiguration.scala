package com.ruchij.config

import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class ServiceConfiguration(httpConfiguration: HttpConfiguration)

object ServiceConfiguration {
  def parse(configObjectSource: ConfigObjectSource): Either[Exception, ServiceConfiguration] =
    configObjectSource.load[ServiceConfiguration].left.map(ConfigReaderException.apply)
}
