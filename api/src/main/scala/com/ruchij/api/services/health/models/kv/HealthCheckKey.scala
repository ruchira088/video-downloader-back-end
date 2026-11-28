package com.ruchij.api.services.health.models.kv

import cats.Applicative
import com.ruchij.core.kv.codecs.KVEncoder
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}
import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

final case class HealthCheckKey(dateTime: Instant) extends KVStoreKey

object HealthCheckKey {
  implicit case object HealthCheckKeySpace extends KeySpace[HealthCheckKey, Instant] {
    override val name: String = "health-check"
    override val maybeTtl: Option[FiniteDuration] = Some(10 seconds)
  }

  implicit def keySpacedKeyEncoder[F[_]: Applicative]: KeySpacedKeyEncoder[F, HealthCheckKey] =
    KeySpacedKeyEncoder.of(HealthCheckKeySpace, KVEncoder[F, Instant].coMap[HealthCheckKey](_.dateTime))
}
