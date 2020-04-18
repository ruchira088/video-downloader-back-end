package com.ruchij.services.health

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import com.ruchij.services.health.models.ServiceInformation
import org.joda.time.DateTime

class HealthServiceImpl[F[_]: Clock: Sync] extends HealthService[F] {
  override def serviceInformation(): F[ServiceInformation] =
    Clock[F].realTime(TimeUnit.MILLISECONDS)
      .flatMap(timestamp => ServiceInformation.create(new DateTime(timestamp)))
}
