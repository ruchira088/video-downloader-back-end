package com.ruchij.api.services.health.models

import shapeless.Generic.Aux
import shapeless.{::, Generic, HNil}

import scala.language.postfixOps

case class HealthCheck(
  database: HealthStatus,
  fileRepository: HealthStatus,
  keyValueStore: HealthStatus,
  pubSubStatus: HealthStatus
) { self =>
  val isHealthy: Boolean =
    HealthCheck.generic.to(self).toList.forall(_ == HealthStatus.Healthy)
}

object HealthCheck {
  val generic: Aux[HealthCheck, HealthStatus :: HealthStatus :: HealthStatus :: HealthStatus :: HNil] =
    Generic[HealthCheck]
}
