package com.ruchij.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.services.health.HealthService
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

object ServiceRoutes {
  def apply[F[_]: Sync](healthService: HealthService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root =>
        healthService.serviceInformation()
          .flatMap(serviceInformation => Ok(serviceInformation))
    }
  }
}
