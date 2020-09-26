package com.ruchij.api.services.health

import com.ruchij.api.services.health.models.{HealthCheck, ServiceInformation}

trait HealthService[F[_]] {
  val serviceInformation: F[ServiceInformation]

  val healthCheck: F[HealthCheck]
}
