package com.ruchij.services.health

import com.ruchij.services.health.models.ServiceInformation

trait HealthService[F[_]] {
  def serviceInformation(): F[ServiceInformation]
}
