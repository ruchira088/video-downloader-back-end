package com.ruchij.api.services.health.models

import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class HealthCheckModelsSpec extends AnyFlatSpec with Matchers {

  private val timestamp = new DateTime(2024, 5, 15, 10, 30)

  "HealthCheckMessage" should "store instanceId and dateTime" in {
    val message = HealthCheckMessage("instance-123", timestamp)

    message.instanceId mustBe "instance-123"
    message.dateTime mustBe timestamp
  }

  it should "support equality" in {
    val message1 = HealthCheckMessage("instance-123", timestamp)
    val message2 = HealthCheckMessage("instance-123", timestamp)

    message1 mustBe message2
  }

  it should "support copy" in {
    val message = HealthCheckMessage("instance-123", timestamp)
    val newTimestamp = timestamp.plusHours(1)
    val copied = message.copy(dateTime = newTimestamp)

    copied.instanceId mustBe "instance-123"
    copied.dateTime mustBe newTimestamp
  }

  "HealthCheckTopic" should "have name 'health-check'" in {
    HealthCheckMessage.HealthCheckTopic.name mustBe "health-check"
  }

  "HealthCheckKey" should "store dateTime" in {
    val key = HealthCheckKey(timestamp)

    key.dateTime mustBe timestamp
  }

  it should "support equality" in {
    val key1 = HealthCheckKey(timestamp)
    val key2 = HealthCheckKey(timestamp)

    key1 mustBe key2
  }

  "HealthCheckKeySpace" should "have name 'health-check'" in {
    HealthCheckKey.HealthCheckKeySpace.name mustBe "health-check"
  }

  it should "have TTL of 10 seconds" in {
    HealthCheckKey.HealthCheckKeySpace.maybeTtl mustBe Some(10.seconds)
  }
}
