package com.ruchij.fallback.web

import cats.effect.Async
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.web.middleware.{ExceptionHandler, NotFoundHandler, RequestContextMiddleware}
import com.ruchij.api.web.routes.AuthenticationRoutes
import com.ruchij.fallback.services.health.HealthService
import com.ruchij.fallback.services.user.UserService
import com.ruchij.fallback.web.routes.{ServiceRoutes, UserRoutes}
import org.http4s.{ContextRoutes, HttpApp}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.ContextRouter
import org.http4s.server.middleware.GZip

object Routes {

  def apply[F[_]: Async](userService: UserService[F], authenticationService: AuthenticationService[F], healthService: HealthService[F]): HttpApp[F] = {
    implicit val http4sDsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val contextRouter: ContextRoutes[RequestContext, F] =
      ContextRouter[F, RequestContext](
        "/user" -> UserRoutes[F](userService),
        "/authentication" -> AuthenticationRoutes[F](authenticationService),
        "/service" -> ServiceRoutes[F](healthService)
      )

    GZip {
      RequestContextMiddleware[F] {
        ExceptionHandler[F, RequestContext] {
          NotFoundHandler[F, RequestContext] {
            contextRouter
          }
        }
      }
    }
  }

}
