package com.ruchij.fallback.web.routes

import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.fallback.services.health.HealthService
import com.ruchij.core.circe.Encoders._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.dsl.Http4sDsl

object ServiceRoutes {

  def apply[F[_]: Sync](healthService: HealthService[F])(implicit http4sDsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import http4sDsl._

    ContextRoutes.of {
      case GET -> Root / "info" as _ =>
        healthService.serviceInformation.flatMap(serviceInformation => Ok(serviceInformation))

      case GET -> Root / "health" as _ =>
        healthService.healthCheck.flatMap {
          healthCheck => if (healthCheck.isHealthy) Ok(healthCheck) else ServiceUnavailable(healthCheck)
        }
    }
  }

}
