package com.ruchij.services.health

import cats.effect.{Clock, Sync}
import com.ruchij.config.ApplicationInformation
import com.ruchij.services.health.models.ServiceInformation

class HealthServiceImpl[F[_]: Clock: Sync](applicationInformation: ApplicationInformation) extends HealthService[F] {
  override def serviceInformation(): F[ServiceInformation] =
    ServiceInformation.create(applicationInformation)
}
