package com.ruchij.fallback.services.health.models

import com.ruchij.api.services.health.models.HealthStatus
import shapeless.Generic

final case class HealthCheck (database: HealthStatus) { self =>
  val isHealthy: Boolean = Generic[HealthCheck].to(self).toList.forall(_ == HealthStatus.Healthy)
}
