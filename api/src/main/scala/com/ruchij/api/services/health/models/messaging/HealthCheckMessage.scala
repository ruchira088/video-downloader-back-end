package com.ruchij.api.services.health.models.messaging

import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.core.messaging.MessagingTopic
import io.circe.generic.semiauto.deriveCodec
import java.time.Instant
import vulcan.Codec
import vulcan.generic._

final case class HealthCheckMessage(instanceId: String, dateTime: Instant)

object HealthCheckMessage {
  implicit case object HealthCheckTopic extends MessagingTopic[HealthCheckMessage] {
    override val name: String = "health-check"

    override val avroCodec: Codec[HealthCheckMessage] = Codec.derive[HealthCheckMessage]

    override val jsonCodec: io.circe.Codec[HealthCheckMessage] = deriveCodec[HealthCheckMessage]
  }
}