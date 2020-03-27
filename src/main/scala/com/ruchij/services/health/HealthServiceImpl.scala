package com.ruchij.services.health

import java.util.concurrent.TimeUnit

import cats.Functor
import cats.effect.Clock
import cats.implicits._
import com.ruchij.services.health.models.ServiceInformation
import org.joda.time.DateTime

class HealthServiceImpl[F[_]: Clock: Functor] extends HealthService[F] {
  override def serviceInformation(): F[ServiceInformation] =
    Clock[F].realTime(TimeUnit.MILLISECONDS)
      .map(timestamp => ServiceInformation(new DateTime(timestamp)))
}
