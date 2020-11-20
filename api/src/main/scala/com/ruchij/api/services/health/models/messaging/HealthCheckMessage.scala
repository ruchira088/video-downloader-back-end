package com.ruchij.api.services.health.models.messaging

import com.ruchij.core.messaging.kafka.Codecs._
import com.ruchij.core.messaging.kafka.KafkaTopic
import org.joda.time.DateTime
import vulcan.Codec
import vulcan.generic._

case class HealthCheckMessage(instanceId: String, dateTime: DateTime)

object HealthCheckMessage {
  implicit case object HealthCheckTopic extends KafkaTopic[HealthCheckMessage] {
    override val name: String = "health-check"

    override val codec: Codec[HealthCheckMessage] = Codec.derive[HealthCheckMessage]
  }
}