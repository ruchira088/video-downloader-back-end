package com.ruchij.api.config

import com.comcast.ip4s.{Host, Port}
import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader

final case class HttpConfiguration(host: Host, port: Port, private val allowedOrigins: Option[Set[String]])
    derives ConfigReader {
  val allowedOriginHosts: Set[String] = allowedOrigins.getOrElse(Set.empty)
}
