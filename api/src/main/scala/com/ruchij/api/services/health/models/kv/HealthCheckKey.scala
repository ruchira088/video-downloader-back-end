package com.ruchij.api.services.health.models.kv

import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import org.joda.time.DateTime

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

case class HealthCheckKey(dateTime: DateTime) extends KVStoreKey

object HealthCheckKey {
  implicit case object HealthCheckKeySpace extends KeySpace[HealthCheckKey, DateTime] {
    override val name: String = "health-check"
    override val maybeTtl: Option[FiniteDuration] = Some(10 seconds)
  }
}
