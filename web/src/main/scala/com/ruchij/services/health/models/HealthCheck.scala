package com.ruchij.services.health.models

import shapeless.Generic.Aux
import shapeless.{::, Generic, HNil}

case class HealthCheck(database: HealthStatus, fileRepository: HealthStatus) { self =>
  val isHealthy: Boolean =
    HealthCheck.generic.to(self).toList.forall(_ == HealthStatus.Healthy)
}

object HealthCheck {
  val generic: Aux[HealthCheck, HealthStatus :: HealthStatus :: HNil] = Generic[HealthCheck]
}
