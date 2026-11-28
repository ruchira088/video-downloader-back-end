package com.ruchij.core.config

import pureconfig.ConfigReader

final case class RedisConfiguration(hostname: String, port: Int, password: Option[String]) derives ConfigReader {
  val uri = s"redis://${password.fold("")(_ + "@")}$hostname:$port"
}
