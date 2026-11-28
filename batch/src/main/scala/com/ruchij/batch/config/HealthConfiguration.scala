package com.ruchij.batch.config

import com.comcast.ip4s.*
import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader

final case class HealthConfiguration(host: Host = host"0.0.0.0", port: Port = port"9001") derives ConfigReader
