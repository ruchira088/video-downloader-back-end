package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.*
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class HttpProxyConfiguration(proxyUrl: Uri)

object HttpProxyConfiguration {
  implicit val configReader: ConfigReader[HttpProxyConfiguration] = deriveReader[HttpProxyConfiguration]
}
