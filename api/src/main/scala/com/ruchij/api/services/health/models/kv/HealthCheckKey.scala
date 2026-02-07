package com.ruchij.api.services.health.models.kv

import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

final case class HealthCheckKey(dateTime: Instant) extends KVStoreKey

object HealthCheckKey {
  implicit case object HealthCheckKeySpace extends KeySpace[HealthCheckKey, Instant] {
    override val name: String = "health-check"
    override val maybeTtl: Option[FiniteDuration] = Some(10 seconds)
  }
}
