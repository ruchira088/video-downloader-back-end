package com.ruchij.api.config

import com.comcast.ip4s.{Host, Port}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class HttpConfigurationSpec extends AnyFlatSpec with Matchers {

  private val host = Host.fromString("0.0.0.0").get
  private val port = Port.fromInt(8080).get

  "HttpConfiguration" should "have allowedOriginHosts empty when allowedOrigins is None" in {
    val config = HttpConfiguration(host, port, None)
    config.allowedOriginHosts mustBe Set.empty
  }

  it should "have allowedOriginHosts empty when allowedOrigins is Some(empty set)" in {
    val config = HttpConfiguration(host, port, Some(Set.empty))
    config.allowedOriginHosts mustBe Set.empty
  }

  it should "return correct allowedOriginHosts when allowedOrigins has values" in {
    val origins = Set("example.com", "api.example.com", "localhost:3000")
    val config = HttpConfiguration(host, port, Some(origins))

    config.allowedOriginHosts mustBe origins
    config.allowedOriginHosts must contain("example.com")
    config.allowedOriginHosts must contain("api.example.com")
    config.allowedOriginHosts must contain("localhost:3000")
  }

  it should "store host and port correctly" in {
    val config = HttpConfiguration(host, port, None)

    config.host mustBe host
    config.port mustBe port
  }

  "allowedOriginHosts" should "be derived from allowedOrigins" in {
    val origins = Set("origin1.com", "origin2.com")
    val config = HttpConfiguration(host, port, Some(origins))

    config.allowedOriginHosts must have size 2
    config.allowedOriginHosts mustBe origins
  }
}
