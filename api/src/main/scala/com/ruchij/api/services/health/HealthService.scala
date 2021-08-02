package com.ruchij.api.services.health

import com.ruchij.api.services.health.models.{HealthCheck, ServiceInformation}

trait HealthService[F[_]] {
  def serviceInformation: F[ServiceInformation]

  def healthCheck: F[HealthCheck]
}
