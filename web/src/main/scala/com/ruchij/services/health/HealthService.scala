package com.ruchij.services.health

import com.ruchij.services.health.models.{HealthCheck, ServiceInformation}

trait HealthService[F[_]] {
  val serviceInformation: F[ServiceInformation]

  val healthCheck: F[HealthCheck]
}
