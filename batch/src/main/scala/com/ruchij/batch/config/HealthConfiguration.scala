package com.ruchij.batch.config

import com.comcast.ip4s.*
import com.ruchij.core.config.PureConfigReaders.*
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class HealthConfiguration(host: Host = host"0.0.0.0", port: Port = port"9001")

object HealthConfiguration {
  implicit val configReader: ConfigReader[HealthConfiguration] = deriveReader[HealthConfiguration]
}
