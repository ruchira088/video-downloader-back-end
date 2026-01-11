package com.ruchij.core.config

import com.comcast.ip4s.{Host, Port}
import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.services.models.Order
import com.typesafe.config.ConfigFactory
import org.http4s.Uri
import org.joda.time.LocalTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class PureConfigReadersSpec extends AnyFlatSpec with Matchers {

  "localTimePureConfigReader" should "parse valid local time" in {
    val config = ConfigFactory.parseString("""value = "10:30:00" """)
    val result = ConfigSource.fromConfig(config).at("value").load[LocalTime]
    result.isRight mustBe true
    result.toOption.get.getHourOfDay mustBe 10
    result.toOption.get.getMinuteOfHour mustBe 30
  }

  it should "parse midnight" in {
    val config = ConfigFactory.parseString("""value = "00:00:00" """)
    val result = ConfigSource.fromConfig(config).at("value").load[LocalTime]
    result.isRight mustBe true
    result.toOption.get.getHourOfDay mustBe 0
  }

  it should "fail for invalid time format" in {
    val config = ConfigFactory.parseString("""value = "invalid-time" """)
    val result = ConfigSource.fromConfig(config).at("value").load[LocalTime]
    result.isLeft mustBe true
  }

  "hostConfigReader" should "parse valid hostname" in {
    val config = ConfigFactory.parseString("""value = "localhost" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Host]
    result.isRight mustBe true
    result.toOption.get.toString mustBe "localhost"
  }

  it should "parse IP address" in {
    val config = ConfigFactory.parseString("""value = "127.0.0.1" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Host]
    result.isRight mustBe true
    result.toOption.get.toString mustBe "127.0.0.1"
  }

  it should "fail for empty string" in {
    val config = ConfigFactory.parseString("""value = "" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Host]
    result.isLeft mustBe true
  }

  "portConfigReader" should "parse valid port" in {
    val config = ConfigFactory.parseString("""value = "8080" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Port]
    result.isRight mustBe true
    result.toOption.get.value mustBe 8080
  }

  it should "parse port 443" in {
    val config = ConfigFactory.parseString("""value = "443" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Port]
    result.isRight mustBe true
    result.toOption.get.value mustBe 443
  }

  it should "fail for invalid port" in {
    val config = ConfigFactory.parseString("""value = "invalid" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Port]
    result.isLeft mustBe true
  }

  "uriPureConfigReader" should "parse valid URI" in {
    val config = ConfigFactory.parseString("""value = "https://example.com" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Uri]
    result.isRight mustBe true
    result.toOption.get.toString mustBe "https://example.com"
  }

  it should "parse URI with path" in {
    val config = ConfigFactory.parseString("""value = "https://example.com/path/to/resource" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Uri]
    result.isRight mustBe true
    result.toOption.get.path.renderString mustBe "/path/to/resource"
  }

  it should "parse URI with query params" in {
    val config = ConfigFactory.parseString("""value = "https://example.com?key=value" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Uri]
    result.isRight mustBe true
    result.toOption.get.query.params.exists { case (k, v) => k == "key" && v.contains("value") } mustBe true
  }

  it should "fail for empty string" in {
    val config = ConfigFactory.parseString("""value = "" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Uri]
    result.isLeft mustBe true
  }

  "stringIterableConfigReader" should "parse semicolon-separated list" in {
    val config = ConfigFactory.parseString("""value = "one;two;three" """)
    val result = ConfigSource.fromConfig(config).at("value").load[List[String]]
    result.isRight mustBe true
    result.toOption.get mustBe List("one", "two", "three")
  }

  it should "handle whitespace around items" in {
    val config = ConfigFactory.parseString("""value = " one ; two ; three " """)
    val result = ConfigSource.fromConfig(config).at("value").load[List[String]]
    result.isRight mustBe true
    result.toOption.get mustBe List("one", "two", "three")
  }

  it should "filter out empty items" in {
    val config = ConfigFactory.parseString("""value = "one;;two" """)
    val result = ConfigSource.fromConfig(config).at("value").load[List[String]]
    result.isRight mustBe true
    result.toOption.get mustBe List("one", "two")
  }

  it should "return empty list for empty string" in {
    val config = ConfigFactory.parseString("""value = "" """)
    val result = ConfigSource.fromConfig(config).at("value").load[List[String]]
    result.isRight mustBe true
    result.toOption.get mustBe List()
  }

  it should "parse single item" in {
    val config = ConfigFactory.parseString("""value = "only-one" """)
    val result = ConfigSource.fromConfig(config).at("value").load[List[String]]
    result.isRight mustBe true
    result.toOption.get mustBe List("only-one")
  }

  "enumPureConfigReader" should "parse valid SchedulingStatus" in {
    val config = ConfigFactory.parseString("""value = "Queued" """)
    val result = ConfigSource.fromConfig(config).at("value").load[SchedulingStatus]
    result.isRight mustBe true
    result.toOption.get mustBe SchedulingStatus.Queued
  }

  it should "parse case-insensitively" in {
    val config = ConfigFactory.parseString("""value = "completed" """)
    val result = ConfigSource.fromConfig(config).at("value").load[SchedulingStatus]
    result.isRight mustBe true
    result.toOption.get mustBe SchedulingStatus.Completed
  }

  it should "parse Order enum" in {
    val config = ConfigFactory.parseString("""value = "asc" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Order]
    result.isRight mustBe true
    result.toOption.get mustBe Order.Ascending
  }

  it should "fail for invalid enum value" in {
    val config = ConfigFactory.parseString("""value = "InvalidStatus" """)
    val result = ConfigSource.fromConfig(config).at("value").load[SchedulingStatus]
    result.isLeft mustBe true
  }

  it should "fail for empty string" in {
    val config = ConfigFactory.parseString("""value = "" """)
    val result = ConfigSource.fromConfig(config).at("value").load[SchedulingStatus]
    result.isLeft mustBe true
  }

  "stringIterableConfigReader with Set" should "parse into Set" in {
    val config = ConfigFactory.parseString("""value = "one;two;one" """)
    val result = ConfigSource.fromConfig(config).at("value").load[Set[String]]
    result.isRight mustBe true
    result.toOption.get mustBe Set("one", "two")
  }
}
