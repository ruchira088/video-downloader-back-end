package com.ruchij.core.config

final case class RedisConfiguration(hostname: String, port: Int, password: Option[String]) {
  val uri = s"redis://${password.fold("")(_ + "@")}$hostname:$port"
}