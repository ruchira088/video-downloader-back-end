package com.ruchij.api.services.health.models.messaging

import com.ruchij.core.messaging.kafka.KafkaTopic
import java.time.Instant
import vulcan.Codec
import vulcan.generic._

final case class HealthCheckMessage(instanceId: String, dateTime: Instant)

object HealthCheckMessage {
  implicit case object HealthCheckTopic extends KafkaTopic[HealthCheckMessage] {
    override val name: String = "health-check"

    override val codec: Codec[HealthCheckMessage] = Codec.derive[HealthCheckMessage]
  }
}