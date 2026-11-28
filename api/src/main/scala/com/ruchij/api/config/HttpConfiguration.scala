package com.ruchij.api.config

import com.comcast.ip4s.{Host, Port}
import com.ruchij.core.config.PureConfigReaders._
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class HttpConfiguration(host: Host, port: Port, private val allowedOrigins: Option[Set[String]]) {
  val allowedOriginHosts: Set[String] = allowedOrigins.getOrElse(Set.empty)
}

object HttpConfiguration {
  implicit val configReader: ConfigReader[HttpConfiguration] = deriveReader[HttpConfiguration]
}
