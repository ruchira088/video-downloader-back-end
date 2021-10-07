package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.core.circe.Encoders.{dateTimeEncoder, enumEncoder}
import io.circe.generic.auto._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.ContextRoutes
import org.http4s.dsl.Http4sDsl

object ServiceRoutes {
  def apply[F[_]: Sync](healthService: HealthService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import dsl._

    ContextRoutes.of[RequestContext, F] {
      case GET -> Root / "info" as _ =>
        healthService.serviceInformation
          .flatMap(serviceInformation => Ok(serviceInformation))

      case GET -> Root / "health" as _ =>
        healthService.healthCheck
          .flatMap { healthCheck =>
            if (healthCheck.isHealthy) Ok(healthCheck) else ServiceUnavailable(healthCheck)
          }
    }
  }
}
