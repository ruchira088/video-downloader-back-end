package com.ruchij.config

case class RedisConfiguration(hostname: String, port: Int, password: String) {
  val uri = s"redis://$password@$hostname:$port"
}