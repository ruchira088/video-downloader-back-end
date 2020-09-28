package com.ruchij.api.services.health.models

import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import org.joda.time.DateTime
import shapeless.Generic.Aux
import shapeless.{::, Generic, HNil}

import scala.concurrent.duration._
import scala.language.postfixOps

case class HealthCheck(database: HealthStatus, fileRepository: HealthStatus, keyValueStore: HealthStatus) { self =>
  val isHealthy: Boolean =
    HealthCheck.generic.to(self).toList.forall(_ == HealthStatus.Healthy)
}

object HealthCheck {
  case class HealthCheckKey(dateTime: DateTime) extends KVStoreKey

  implicit case object HealthCheckKeySpace extends KeySpace[HealthCheckKey, DateTime] {
    override val name: String = "health-check"
    override val ttl: FiniteDuration = 10 seconds
  }

  val generic: Aux[HealthCheck, HealthStatus :: HealthStatus :: HealthStatus :: HNil] = Generic[HealthCheck]
}
