package com.ruchij.fallback.services.health

import com.ruchij.api.services.health.models.ServiceInformation
import com.ruchij.fallback.services.health.models.HealthCheck

trait HealthService[F[_]] {
  def serviceInformation: F[ServiceInformation]

  def healthCheck: F[HealthCheck]
}