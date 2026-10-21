package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.models.Context.{AuthenticatedRequestContext, RequestContext}
import com.ruchij.api.web.middleware.Authenticator
import com.ruchij.core.circe.Encoders.{instantEncoder, enumEncoder}
import io.circe.generic.auto._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.ContextRoutes
import org.http4s.dsl.Http4sDsl

object ServiceRoutes {
  def apply[F[_]: Sync](healthService: HealthService[F], authenticationService: AuthenticationService[F])(
    implicit dsl: Http4sDsl[F]
  ): ContextRoutes[RequestContext, F] = {
    import dsl._

    // Used by the k8s liveness and readiness probes, so it must not require authentication
    val unauthenticatedRoutes =
      ContextRoutes.of[RequestContext, F] {
        case GET -> Root / "info" as _ =>
          healthService.serviceInformation
            .flatMap(serviceInformation => Ok(serviceInformation))
      }

    // The health check writes to the storage folders, makes outbound calls and exposes the folder paths
    val authenticatedRoutes =
      ContextRoutes.of[AuthenticatedRequestContext, F] {
        case GET -> Root / "health" as _ =>
          healthService.healthCheck
            .flatMap { healthCheck =>
              if (healthCheck.isHealthy) Ok(healthCheck) else ServiceUnavailable(healthCheck)
            }
      }

    unauthenticatedRoutes <+>
      Authenticator.middleware[F](authenticationService, strict = true).apply(authenticatedRoutes)
  }
}
