package com.ruchij.batch.config

import com.comcast.ip4s._

final case class HealthConfiguration(host: Host = host"0.0.0.0", port: Port = port"9001")
