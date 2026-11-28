package com.ruchij.core.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class RedisConfiguration(hostname: String, port: Int, password: Option[String]) {
  val uri = s"redis://${password.fold("")(_ + "@")}$hostname:$port"
}

object RedisConfiguration {
  implicit val configReader: ConfigReader[RedisConfiguration] = deriveReader[RedisConfiguration]
}
